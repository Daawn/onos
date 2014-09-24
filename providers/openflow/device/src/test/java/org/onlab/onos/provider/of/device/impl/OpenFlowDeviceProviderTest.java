package org.onlab.onos.provider.of.device.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.onlab.onos.net.Device.Type.*;
import static org.onlab.onos.net.MastershipRole.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.onlab.onos.net.DefaultDevice;
import org.onlab.onos.net.Device;
import org.onlab.onos.net.DeviceId;
import org.onlab.onos.net.MastershipRole;
import org.onlab.onos.net.device.DeviceDescription;
import org.onlab.onos.net.device.DeviceProvider;
import org.onlab.onos.net.device.DeviceProviderRegistry;
import org.onlab.onos.net.device.DeviceProviderService;
import org.onlab.onos.net.device.PortDescription;
import org.onlab.onos.net.provider.ProviderId;
import org.onlab.onos.openflow.controller.Dpid;
import org.onlab.onos.openflow.controller.OpenFlowController;
import org.onlab.onos.openflow.controller.OpenFlowEventListener;
import org.onlab.onos.openflow.controller.OpenFlowSwitch;
import org.onlab.onos.openflow.controller.OpenFlowSwitchListener;
import org.onlab.onos.openflow.controller.PacketListener;
import org.onlab.onos.openflow.controller.RoleState;
import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPortDesc;
import org.projectfloodlight.openflow.protocol.OFPortReason;
import org.projectfloodlight.openflow.protocol.OFPortStatus;
import org.projectfloodlight.openflow.protocol.ver10.OFFactoryVer10;
import org.projectfloodlight.openflow.types.OFPort;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;

public class OpenFlowDeviceProviderTest {

    private static final ProviderId PID = new ProviderId("of", "test");
    private static final DeviceId DID1 = DeviceId.deviceId("of:0000000000000001");
    private static final Dpid DPID1 = Dpid.dpid(DID1.uri());

    private static final OFPortDesc PD1 = portDesc(1);
    private static final OFPortDesc PD2 = portDesc(2);
    private static final OFPortDesc PD3 = portDesc(3);

    private static final List<OFPortDesc> PLIST = Lists.newArrayList(PD1, PD2);

    private static final Device DEV1 =
            new DefaultDevice(PID, DID1, SWITCH, "", "", "", "");

    private static final TestOpenFlowSwitch SW1 = new TestOpenFlowSwitch();

    private final OpenFlowDeviceProvider provider = new OpenFlowDeviceProvider();
    private final TestDeviceRegistry registry = new TestDeviceRegistry();
    private final TestController controller = new TestController();

    @Before
    public void startUp() {
        provider.providerRegistry = registry;
        provider.controller = controller;
        controller.switchMap.put(DPID1, SW1);
        provider.activate();
        assertNotNull("provider should be registered", registry.provider);
        assertNotNull("listener should be registered", controller.listener);
        assertEquals("devices not added", 1, registry.connected.size());
        assertEquals("ports not added", 2, registry.ports.get(DID1).size());
    }

    @After
    public void tearDown() {
        provider.deactivate();
        assertTrue("devices should be removed", registry.connected.isEmpty());
        assertNull("listener should be removed", controller.listener);
        provider.controller = null;
        provider.providerRegistry = null;
    }

    @Test
    public void roleChanged() {
        provider.roleChanged(DEV1, MASTER);
        assertEquals("Should be MASTER", RoleState.MASTER, controller.roleMap.get(DPID1));
        provider.roleChanged(DEV1, STANDBY);
        assertEquals("Should be EQUAL", RoleState.EQUAL, controller.roleMap.get(DPID1));
        provider.roleChanged(DEV1, NONE);
        assertEquals("Should be SLAVE", RoleState.SLAVE, controller.roleMap.get(DPID1));
    }

    @Test
    public void triggerProbe() {
        // TODO
    }

    @Test
    public void switchRemoved() {
        controller.listener.switchRemoved(DPID1);
        assertTrue("device not removed", registry.connected.isEmpty());
    }

    @Test
    public void portChanged() {
        OFPortStatus stat = SW1.factory().buildPortStatus()
                .setReason(OFPortReason.ADD)
                .setDesc(PD3)
                .build();
        controller.listener.portChanged(DPID1, stat);
        assertNotNull("never went throught the provider service", registry.descr);
        assertEquals("port status unhandled", 3, registry.ports.get(DID1).size());
    }

    private static OFPortDesc portDesc(int port) {
        OFPortDesc.Builder builder = OFFactoryVer10.INSTANCE.buildPortDesc();
        builder.setPortNo(OFPort.of(port));

        return builder.build();
    }

    private class TestDeviceRegistry implements DeviceProviderRegistry {
        DeviceProvider provider;
        Set<DeviceId> connected = new HashSet<DeviceId>();
        Multimap<DeviceId, PortDescription> ports = HashMultimap.create();
        PortDescription descr = null;

        @Override
        public DeviceProviderService register(DeviceProvider provider) {
            this.provider = provider;
            return new TestProviderService();
        }

        @Override
        public void unregister(DeviceProvider provider) {
        }

        @Override
        public Set<ProviderId> getProviders() {
            return null;
        }

        private class TestProviderService implements DeviceProviderService {

            @Override
            public DeviceProvider provider() {
                return null;
            }

            @Override
            public void deviceConnected(DeviceId deviceId,
                    DeviceDescription deviceDescription) {
                connected.add(deviceId);
            }

            @Override
            public void deviceDisconnected(DeviceId deviceId) {
                connected.remove(deviceId);
                ports.removeAll(deviceId);
            }

            @Override
            public void updatePorts(DeviceId deviceId,
                    List<PortDescription> portDescriptions) {
                for (PortDescription p : portDescriptions) {
                    ports.put(deviceId, p);
                }
            }

            @Override
            public void portStatusChanged(DeviceId deviceId,
                    PortDescription portDescription) {
                ports.put(deviceId, portDescription);
                descr = portDescription;
            }

            @Override
            public void unableToAssertRole(DeviceId deviceId, MastershipRole role) {
                // FIXME: add fixture core when tests are done on this
            }

        }
    }

    private class TestController implements OpenFlowController {
        OpenFlowSwitchListener listener = null;
        Map<Dpid, RoleState> roleMap = new HashMap<Dpid, RoleState>();
        Map<Dpid, OpenFlowSwitch> switchMap = new HashMap<Dpid, OpenFlowSwitch>();

        @Override
        public Iterable<OpenFlowSwitch> getSwitches() {
            return switchMap.values();
        }

        @Override
        public Iterable<OpenFlowSwitch> getMasterSwitches() {
            return null;
        }

        @Override
        public Iterable<OpenFlowSwitch> getEqualSwitches() {
            return null;
        }

        @Override
        public OpenFlowSwitch getSwitch(Dpid dpid) {
            return switchMap.get(dpid);
        }

        @Override
        public OpenFlowSwitch getMasterSwitch(Dpid dpid) {
            return null;
        }

        @Override
        public OpenFlowSwitch getEqualSwitch(Dpid dpid) {

            return null;
        }

        @Override
        public void addListener(OpenFlowSwitchListener listener) {
            this.listener = listener;
        }

        @Override
        public void removeListener(OpenFlowSwitchListener listener) {
            this.listener = null;
        }

        @Override
        public void addPacketListener(int priority, PacketListener listener) {
        }

        @Override
        public void removePacketListener(PacketListener listener) {
        }

        @Override
        public void addEventListener(OpenFlowEventListener listener) {
        }

        @Override
        public void removeEventListener(OpenFlowEventListener listener) {
        }

        @Override
        public void write(Dpid dpid, OFMessage msg) {
        }

        @Override
        public void processPacket(Dpid dpid, OFMessage msg) {
        }

        @Override
        public void setRole(Dpid dpid, RoleState role) {
            roleMap.put(dpid, role);
        }
    }

    private static class TestOpenFlowSwitch implements OpenFlowSwitch {

        RoleState state;
        List<OFMessage> sent = new ArrayList<OFMessage>();
        OFFactory factory = OFFactoryVer10.INSTANCE;

        @Override
        public void sendMsg(OFMessage msg) {
            sent.add(msg);
        }

        @Override
        public void sendMsg(List<OFMessage> msgs) {
        }

        @Override
        public void handleMessage(OFMessage fromSwitch) {
        }

        @Override
        public void setRole(RoleState role) {
            state = role;
        }

        @Override
        public RoleState getRole() {
            return state;
        }

        @Override
        public List<OFPortDesc> getPorts() {
            return PLIST;
        }

        @Override
        public OFFactory factory() {
            return factory;
        }

        @Override
        public String getStringId() {
            return null;
        }

        @Override
        public long getId() {
            return DPID1.value();
        }

        @Override
        public String manfacturerDescription() {
            return null;
        }

        @Override
        public String datapathDescription() {
            return null;
        }

        @Override
        public String hardwareDescription() {
            return null;
        }

        @Override
        public String softwareDescription() {
            return null;
        }

        @Override
        public String serialNumber() {
            return null;
        }

        @Override
        public void disconnectSwitch() {
        }

    }

}
