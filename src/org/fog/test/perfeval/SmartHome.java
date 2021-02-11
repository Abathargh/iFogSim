package org.fog.test.perfeval;

import java.util.List;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedList;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.cloudbus.cloudsim.sdn.overbooking.BwProvisionerOverbooking;
import org.cloudbus.cloudsim.sdn.overbooking.PeProvisionerOverbooking;
import org.fog.application.AppEdge;
import org.fog.application.AppLoop;
import org.fog.application.Application;
import org.fog.application.selectivity.FractionalSelectivity;
import org.fog.entities.Actuator;
import org.fog.entities.FogBroker;
import org.fog.entities.FogDevice;
import org.fog.entities.FogDeviceCharacteristics;
import org.fog.entities.Sensor;
import org.fog.entities.Tuple;
import org.fog.placement.Controller;
import org.fog.placement.ModuleMapping;
import org.fog.placement.ModulePlacementEdgewards;
import org.fog.placement.ModulePlacementMapping;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.StreamOperatorScheduler;
import org.fog.utils.FogLinearPowerModel;
import org.fog.utils.FogUtils;
import org.fog.utils.TimeKeeper;
import org.fog.utils.distribution.DeterministicDistribution;


public class SmartHome {
    static final int NUM_HOUSES = 3;

	static List<FogDevice> fogDevices = new ArrayList<FogDevice>();
	static List<Sensor> sensors = new ArrayList<Sensor>();
	static List<Actuator> actuators = new ArrayList<Actuator>();

	private static boolean CLOUD = false;

    private static final String SENSOR = "GenericSensor";
    private static final String ACTUATOR = "GenericActuator";

    private final static int NUM_SENSORS = 3;
    private final static int NUM_ACTUATORS = 3;

    enum Module {
        Sensor, DataCache, WebApp, Classifier, Actuator;
    }

    enum Edge {
        SensorData, DataEvent, DataTable, SituationIdentifier, 
        Situation, GenericSensorData, GenericActuatorData; 
    }

    public static Application createApplication(String appId, int userId) {
        Application app = Application.createApplication(appId, userId);
        for(Module mod: Module.values()) {
            int ram = (mod.name().equals(Module.Sensor.name()) ||
            mod.name().equals(Module.Actuator.name())) ? 1 : 10;
            app.addAppModule(mod.name(), ram);
        }

        // board -> sensor
        for(int i = 0; i < NUM_SENSORS; i++)
            app.addAppEdge(SENSOR, Module.Sensor.name(), 
                100, 100, Edge.GenericSensorData.name(), Tuple.UP, AppEdge.SENSOR);

        // sensor -> datacache
        app.addAppEdge(Module.Sensor.name(), Module.DataCache.name(), 
            100, 100, Edge.SensorData.name(), Tuple.UP, AppEdge.MODULE);

        // datacache -> webapp
        app.addAppEdge(Module.DataCache.name(), Module.WebApp.name(), 
            100, 100, Edge.DataEvent.name(), Tuple.UP, AppEdge.MODULE);

        // datacache -> classifier
        app.addAppEdge(Module.DataCache.name(), Module.Classifier.name(), 
            100, 100, Edge.DataTable.name(), Tuple.UP, AppEdge.MODULE);

        // classifier -> datacache
        app.addAppEdge(Module.Classifier.name(), Module.DataCache.name(), 
            100, 100, Edge.SituationIdentifier.name(), Tuple.DOWN, AppEdge.MODULE);

        // datacache -> actuator
        app.addAppEdge(Module.DataCache.name(), Module.Actuator.name(), 
            100, 100, Edge.Situation.name(), Tuple.DOWN, AppEdge.MODULE);
        
        // actuator -> board
        for(int i = 0; i < NUM_ACTUATORS; i++)
            app.addAppEdge(Module.Actuator.name(), ACTUATOR,
                100, 100, Edge.GenericActuatorData.name(), Tuple.DOWN, AppEdge.ACTUATOR);
    

        app.addTupleMapping(Module.Sensor.name(), Edge.GenericSensorData.name(), 
            Edge.SensorData.name(), new FractionalSelectivity(1));

        app.addTupleMapping(Module.DataCache.name(), Edge.SensorData.name(), 
            Edge.DataEvent.name(), new FractionalSelectivity(0.9));
        
        app.addTupleMapping(Module.DataCache.name(), Edge.SensorData.name(), 
            Edge.DataTable.name(), new FractionalSelectivity(0.9));
        
        app.addTupleMapping(Module.Classifier.name(), Edge.DataTable.name(), 
            Edge.SituationIdentifier.name(), new FractionalSelectivity(1));
        
        app.addTupleMapping(Module.DataCache.name(), Edge.SituationIdentifier.name(), 
            Edge.Situation.name(), new FractionalSelectivity(1));

        app.addTupleMapping(Module.Sensor.name(), Edge.Situation.name(), 
            Edge.GenericActuatorData.name(), new FractionalSelectivity(1));

        
        AppLoop webappLoop = new AppLoop(new ArrayList<String>(){
            {
                add(Module.Sensor.name());
                add(Module.DataCache.name());
                add(Module.WebApp.name());
            }
        });

        AppLoop dataLoop = new AppLoop(new ArrayList<String>(){
            {
                add(Module.Sensor.name());
                add(Module.DataCache.name());
                add(Module.Classifier.name());
                add(Module.Actuator.name());
            }
        });

        List<AppLoop> loops = new ArrayList<AppLoop>() {
            {
                add(webappLoop); 
                add(dataLoop);
            }
        };
        app.setLoops(loops);
        return app;
    }

	private static FogDevice createFogDevice(String nodeName, long mips, int ram, long upBw, long downBw,
            int level, double ratePerMips, double busyPower, double idlePower, long storage) {

		List<Pe> peList = new ArrayList<Pe>();

		// createPEs
		peList.add(new Pe(0, new PeProvisionerOverbooking(mips)));

		int hostId = FogUtils.generateEntityId();
		int bw = 10000;

		PowerHost host = new PowerHost(hostId, new RamProvisionerSimple(ram), new BwProvisionerOverbooking(bw), storage,
				peList, new StreamOperatorScheduler(peList), new FogLinearPowerModel(busyPower, idlePower));

		List<Host> hostList = new ArrayList<Host>();
		hostList.add(host);

		String arch = "x86"; // system architecture
		String os = "Linux"; // OS
		String vm = "Xen"; // VM Hypervisor

		double time_zone = 10.0; // time zone where resources are located
		double cost = 3.0; // cost of using processing resources of the element;
		double costPerStorage = 0.001; // cost of the storage
		double costPerMemory = 0.05; // cost of ram
		double costPerBw = 0;
		

		LinkedList<Storage> storageList = new LinkedList<Storage>();

		FogDeviceCharacteristics characteristics = new FogDeviceCharacteristics(arch, os, vm, host, time_zone, cost,
				costPerMemory, costPerStorage, costPerBw);

		FogDevice fogDevice = null;
		try {
			fogDevice = new FogDevice(nodeName, characteristics, new AppModuleAllocationPolicy(hostList), storageList,
					10, upBw, downBw, 0, ratePerMips);
		} catch (Exception e) {
			e.printStackTrace();
		}

		fogDevice.setLevel(level);

		return fogDevice;
	}

    private static void createFogDevices(int userId, String appId) {
		FogDevice cloud = createFogDevice("cloud", 6000, 17100, 3000, 1000, 0, 0.001, 103.16, 83.25, 420000-1);
																											
		cloud.setParentId(-1);
		fogDevices.add(cloud);

		FogDevice gateway = createFogDevice("ISP-gateway", 3000, 4000, 10000, 10000, 1, 0.0, 100.0, 83.0, 10000);
		gateway.setParentId(cloud.getId());
		gateway.setUplinkLatency(1000);
		fogDevices.add(gateway);
		
		for (int i = 0; i < NUM_HOUSES; i++) {
            FogDevice hub = addHub(String.format("%d", i), userId, appId, gateway.getId());
            fogDevices.add(hub);
        }
	}

    private static FogDevice addHub(String id, int userId, String appId, int parentId) {
        FogDevice hub = createFogDevice(String.format("hub-%s", id), 6000, 8000, 1000, 20, 2, 0.0, 3, 1.4, 64000);
        hub.setParentId(parentId);
        hub.setUplinkLatency(5);

        for(int i = 0; i < NUM_SENSORS; i++) {
            Sensor s = new Sensor(String.format("sens#%d%s", i, id), SENSOR, userId, appId, 
                new DeterministicDistribution(5));
            sensors.add(s);
            s.setGatewayDeviceId(hub.getId());
            s.setLatency(10.0);
        }

        for(int i = 0; i < NUM_ACTUATORS; i++) {
            Actuator a = new Actuator(String.format("act#%d%s", i, id), userId, appId, ACTUATOR);
            actuators.add(a);
            a.setGatewayDeviceId(hub.getId());
            a.setLatency(10.0);
        }
        return hub;
    }

    public static void main(String...args) {
        try {
            Log.disable();

			int num_users = 1;
			Calendar calendar = Calendar.getInstance();
			boolean trace_flag = false;

			CloudSim.init(num_users, calendar, trace_flag);

			String appId = "Moody";
			FogBroker broker = new FogBroker("broker");

			Application application = createApplication(appId, broker.getId());

			application.setUserId(broker.getId());
			createFogDevices(broker.getId(), appId);
			Controller controller = null;

			ModuleMapping moduleMapping = ModuleMapping.createModuleMapping();
            moduleMapping.addModuleToDevice(Module.Classifier.name(), "cloud");
            moduleMapping.addModuleToDevice(Module.WebApp.name(), "cloud");		

            for(FogDevice dev: fogDevices) {
                if(dev.getName().startsWith("hub")) {
                    moduleMapping.addModuleToDevice(Module.Sensor.name(), dev.getName());
                    moduleMapping.addModuleToDevice(Module.Actuator.name(), dev.getName());    
                }
            }

			if(CLOUD){
                moduleMapping.addModuleToDevice(Module.DataCache.name(), "cloud");			
            } else {
				for(FogDevice dev: fogDevices) {
                    if(dev.getName().startsWith("hub")) {
                        moduleMapping.addModuleToDevice(Module.DataCache.name(), dev.getName());
                    }
                }
			}



			controller = new Controller("master_controller", fogDevices, sensors, actuators);

			controller.submitApplication(application, (CLOUD)
					? (new ModulePlacementMapping(fogDevices, application, moduleMapping))
					: (new ModulePlacementEdgewards(fogDevices, sensors, actuators, application, moduleMapping)));

			TimeKeeper.getInstance().setSimulationStartTime(calendar.getTimeInMillis());

			CloudSim.startSimulation();

			CloudSim.stopSimulation();

		} catch (Exception e) {
			e.printStackTrace();
		}

    }
}
