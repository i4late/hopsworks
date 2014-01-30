/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package se.kth.kthfsdashboard.virtualization;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.inject.Module;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.ejb.EJB;
import static org.jclouds.Constants.PROPERTY_CONNECTION_TIMEOUT;
import org.jclouds.ContextBuilder;
import static org.jclouds.aws.ec2.reference.AWSEC2Constants.PROPERTY_EC2_AMI_QUERY;
import static org.jclouds.aws.ec2.reference.AWSEC2Constants.PROPERTY_EC2_CC_AMI_QUERY;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.ComputeServiceContext;
import org.jclouds.compute.RunNodesException;
import static org.jclouds.compute.config.ComputeServiceProperties.TIMEOUT_PORT_OPEN;
import static org.jclouds.compute.config.ComputeServiceProperties.TIMEOUT_SCRIPT_COMPLETE;
import org.jclouds.compute.domain.ExecResponse;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.TemplateBuilder;
import org.jclouds.compute.options.RunScriptOptions;
import org.jclouds.ec2.EC2AsyncClient;
import org.jclouds.ec2.EC2Client;
import org.jclouds.ec2.compute.options.EC2TemplateOptions;
import org.jclouds.ec2.domain.IpProtocol;
import org.jclouds.enterprise.config.EnterpriseConfigurationModule;
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.jclouds.openstack.nova.v2_0.NovaApi;
import org.jclouds.openstack.nova.v2_0.NovaAsyncApi;
import org.jclouds.openstack.nova.v2_0.compute.options.NovaTemplateOptions;
import org.jclouds.openstack.nova.v2_0.domain.Ingress;
import org.jclouds.openstack.nova.v2_0.domain.SecurityGroup;
import org.jclouds.openstack.nova.v2_0.extensions.SecurityGroupApi;
import static org.jclouds.openstack.nova.v2_0.predicates.SecurityGroupPredicates.nameEquals;
import org.jclouds.rest.RestContext;
import org.jclouds.scriptbuilder.domain.StatementList;
import org.jclouds.sshj.config.SshjSshClientModule;
import se.kth.kthfsdashboard.host.Host;
import se.kth.kthfsdashboard.host.HostEJB;
import se.kth.kthfsdashboard.provision.DeploymentPhase;
import se.kth.kthfsdashboard.provision.DeploymentProgressFacade;
import se.kth.kthfsdashboard.provision.MessageController;
import se.kth.kthfsdashboard.provision.NodeStatusTracker;
import se.kth.kthfsdashboard.provision.ProviderType;
import se.kth.kthfsdashboard.provision.Provision;
import se.kth.kthfsdashboard.provision.ProvisionController;
import se.kth.kthfsdashboard.provision.RoleMapPorts;
import se.kth.kthfsdashboard.provision.ScriptBuilder;
import se.kth.kthfsdashboard.provision.StoreResults;
import se.kth.kthfsdashboard.virtualization.clusterparser.Cluster;
import se.kth.kthfsdashboard.virtualization.clusterparser.NodeGroup;

/**
 * Representation of a Cluster Virtualization process
 *
 * @author Alberto Lorente Leal <albll@kth.se>
 */
public final class VirtualizedClusterProvision implements Provision {

    @EJB
    private HostEJB hostEJB;
    @EJB
    private DeploymentProgressFacade progressEJB;
    private static final int RETRIES = 5;
    private ComputeService service;
    private ProviderType provider;
    private String id;
    private String key;
    private String endpoint;
    private String publicKey;
    private String privateIP;
    private MessageController messages;
    private Map<String, Set<? extends NodeMetadata>> nodes =
            new ConcurrentHashMap<String, Set<? extends NodeMetadata>>();
    private Map<NodeMetadata, List<String>> mgms = new HashMap();
    private Map<NodeMetadata, List<String>> ndbs = new HashMap();
    private Map<NodeMetadata, List<String>> mysqlds = new HashMap();
    private Map<NodeMetadata, List<String>> namenodes = new HashMap();
    private Map<NodeMetadata, List<String>> datanodes = new HashMap();
    private List<String> ndbsIP = new LinkedList();
    private List<String> mgmIP = new LinkedList();
    private List<String> mySQLClientsIP = new LinkedList();
    private List<String> namenodesIP = new LinkedList();
    private ListeningExecutorService pool;
    private CountDownLatch latch;
    private CopyOnWriteArraySet<NodeMetadata> pendingNodes;
    private int max = 0;
    private int totalNodes = 0;
    private Cluster cluster;

    /*
     * Constructor of a VirtualizedClusterProvision
     */
    public VirtualizedClusterProvision(ProvisionController controller) {
        this.provider = ProviderType.fromString(controller.getProvider());
        this.id = controller.getId();
        this.key = controller.getKey();
        this.endpoint = controller.getKeystoneEndpoint();
        this.privateIP = controller.getPrivateIP();
        this.publicKey = controller.getPublicKey();
        this.messages = controller.getMessages();
        this.service = initContext();
        this.progressEJB = controller.getDeploymentProgressFacade();
        this.hostEJB = controller.getHostEJB();
        this.cluster = controller.getCluster();

    }

    /*
     * Method which creates the securitygroups for the cluster 
     * through the rest client implementations in jclouds.
     */
    @Override
    public void initializeCluster() {
        //Data structures which contains all the mappings of the ports that the roles need to be opened
        progressEJB.createProgress(cluster);
        RoleMapPorts commonTCP = new RoleMapPorts(RoleMapPorts.PortType.COMMON);
        RoleMapPorts portsTCP = new RoleMapPorts(RoleMapPorts.PortType.TCP);
        RoleMapPorts portsUDP = new RoleMapPorts(RoleMapPorts.PortType.UDP);

        String region = cluster.getProvider().getRegion();
        //List to gather  ports, we initialize with the ports defined by the user
        List<Integer> globalPorts = new LinkedList<Integer>(cluster.getGlobal().getAuthorizePorts());

        //All need the kthfsagent ports opened, SSH and web ports
        globalPorts.addAll(Ints.asList(commonTCP.get("hopagent")));
        globalPorts.addAll(Ints.asList(commonTCP.get("ssh")));
        globalPorts.addAll(Ints.asList(commonTCP.get("http&https")));

        //If EC2 client
        if (provider.toString().equals(ProviderType.AWS_EC2.toString())) {
            //Unwrap the compute service context and retrieve a rest context to speak with EC2
            RestContext<EC2Client, EC2AsyncClient> temp = service.getContext().unwrap();
            //Fetch a synchronous rest client
            EC2Client client = temp.getApi();
            //For each group of the security groups
            for (NodeGroup group : cluster.getNodes()) {
                String groupName = "jclouds#" + group.getServices().get(0);// jclouds way of defining groups
                Set<Integer> openTCP = new HashSet<Integer>(); //To avoid opening duplicate ports
                Set<Integer> openUDP = new HashSet<Integer>();// gives exception upon trying to open duplicate ports in a group
                System.out.printf("%d: creating security group: %s%n", System.currentTimeMillis(),
                        group.getServices().get(0));
                //create security group
                messages.addMessage("Creating Security Group: " + group.getServices().get(0));
                try {
                    client.getSecurityGroupServices().createSecurityGroupInRegion(
                            region, groupName, group.getServices().get(0));
                } catch (Exception e) {

                    //If group already exists continue to the next group
                    continue;
                }
                //Open the ports for that group
                //Authorize the ports for TCP and UDP roles in cluster file for that group
                for (String serviceName : group.getServices()) {
                    if (portsTCP.containsKey(serviceName)) {
                        for (int port : portsTCP.get(serviceName)) {
                            if (!openTCP.contains(port)) {
                                client.getSecurityGroupServices().authorizeSecurityGroupIngressInRegion(region,
                                        groupName, IpProtocol.TCP, port, port, "0.0.0.0/0");
                                openTCP.add(port);
                            }
                        }

                        for (int port : portsUDP.get(serviceName)) {
                            if (!openUDP.contains(port)) {
                                client.getSecurityGroupServices().authorizeSecurityGroupIngressInRegion(region,
                                        groupName, IpProtocol.UDP, port, port, "0.0.0.0/0");
                                openUDP.add(port);
                            }
                        }
                    }
                }

                //Authorize the global ports TCP + extra ports of the group
                for (Integer port : globalPorts) {
                    if (!openTCP.contains(port)) {
                        client.getSecurityGroupServices().authorizeSecurityGroupIngressInRegion(region,
                                groupName, IpProtocol.TCP, port.intValue(), port.intValue(), "0.0.0.0/0");
                        openTCP.add(port);
                    }
                }

                if (group.getAuthorizePorts() != null) {
                    for (Integer port : group.getAuthorizePorts()) {
                        if (!openTCP.contains(port)) {
                            client.getSecurityGroupServices().authorizeSecurityGroupIngressInRegion(region,
                                    groupName, IpProtocol.TCP, port.intValue(), port.intValue(), "0.0.0.0/0");
                            openTCP.add(port);
                        }
                    }
                }

                //This is a delay we must use for EC2. There is a limit on REST requests and if we dont limit the
                //bursts of the requests it will fail
                try {
                    Thread.sleep(15000);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        //If openstack nova2 client
        //Similar structure to EC2 but changes apis
        if (provider.toString().equals(ProviderType.OPENSTACK.toString())) {
            RestContext<NovaApi, NovaAsyncApi> temp = service.getContext().unwrap();
            //+++++++++++++++++
            //This stuff below is weird, founded in a code snippet in a workshop on jclouds. Still it works
            //Code not from documentation
            Optional<? extends SecurityGroupApi> securityGroupExt = temp
                    .getApi()
                    .getSecurityGroupExtensionForZone(region);
            System.out.println("  Security Group Support: " + securityGroupExt.isPresent());
            if (securityGroupExt.isPresent()) {
                SecurityGroupApi client = securityGroupExt.get();
                //+++++++++++++++++    
                //For each group of the security groups
                for (NodeGroup group : cluster.getNodes()) {
                    String groupName = "jclouds-" + group.getServices().get(0); //jclouds way of defining groups
                    Set<Integer> openTCP = new HashSet<Integer>(); //To avoid opening duplicate ports
                    Set<Integer> openUDP = new HashSet<Integer>();// gives exception upon trying to open duplicate ports in a group
                    System.out.printf("%d: creating security group: %s%n", System.currentTimeMillis(),
                            group.getServices().get(0));
                    //create security group
                    if (!client.list().anyMatch(nameEquals(groupName))) {
                        messages.addMessage("Creating security group: " + group.getServices().get(0));
                        SecurityGroup created = client.createWithDescription(groupName, group.getServices().get(0));
                        //get the ports

                        //Authorize the ports for TCP and UDP
                        for (String serviceName : group.getServices()) {
                            if (portsTCP.containsKey(serviceName)) {
                                for (int port : portsTCP.get(serviceName)) {
                                    if (!openTCP.contains(port)) {
                                        Ingress ingress = Ingress.builder()
                                                .fromPort(port)
                                                .toPort(port)
                                                .ipProtocol(org.jclouds.openstack.nova.v2_0.domain.IpProtocol.TCP)
                                                .build();
                                        client.createRuleAllowingCidrBlock(created.getId(), ingress, "0.0.0.0/0");
                                        openTCP.add(port);
                                    }

                                }
                                for (int port : portsUDP.get(serviceName)) {
                                    if (!openUDP.contains(port)) {
                                        Ingress ingress = Ingress.builder()
                                                .fromPort(port)
                                                .toPort(port)
                                                .ipProtocol(org.jclouds.openstack.nova.v2_0.domain.IpProtocol.UDP)
                                                .build();
                                        client.createRuleAllowingCidrBlock(created.getId(), ingress, "0.0.0.0/0");
                                        openUDP.add(port);
                                    }

                                }
                            }
                        }


                        //Authorize the global ports
                        for (Integer port : globalPorts) {
                            if (!openTCP.contains(port)) {
                                Ingress ingress = Ingress.builder()
                                        .fromPort(port.intValue())
                                        .toPort(port.intValue())
                                        .ipProtocol(org.jclouds.openstack.nova.v2_0.domain.IpProtocol.TCP)
                                        .build();
                                client.createRuleAllowingCidrBlock(created.getId(), ingress, "0.0.0.0/0");
                                openTCP.add(port);
                            }
                        }

                        if (group.getAuthorizePorts() != null) {
                            for (Integer port : group.getAuthorizePorts()) {
                                if (!openTCP.contains(port)) {
                                    Ingress ingress = Ingress.builder()
                                            .fromPort(port.intValue())
                                            .toPort(port.intValue())
                                            .ipProtocol(org.jclouds.openstack.nova.v2_0.domain.IpProtocol.TCP)
                                            .build();
                                    client.createRuleAllowingCidrBlock(created.getId(), ingress, "0.0.0.0/0");
                                    openTCP.add(port);
                                }
                            }
                        }
                        //This is a delay we must use. There is a limit on REST requests and if we dont limit the
                        //bursts of the requests it will fail
                        try {
                            Thread.sleep(15000);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }

    }

    /*
     * This method iterates over the security groups defined in the cluster file
     * It launches in parallel all the number of nodes specified in the group of the cluster file using the 
     * compute service abstraction from jclouds
     * 
     * If successful, returns true;
     */
    @Override
    public boolean launchNodesBasicSetup() {
        boolean status = false;
        try {
            TemplateBuilder kthfsTemplate = templateKTHFS(cluster, service.templateBuilder());
            //Generate the init script for the nodes
            ScriptBuilder initScript = ScriptBuilder.builder()
                    .scriptType(ScriptBuilder.ScriptType.INIT)
                    .publicKey(publicKey)
                    .gitRepo(cluster.getGlobal().getGit().getRepository())
                    .gitName(cluster.getGlobal().getGit().getUser())
                    .build();
            selectProviderTemplateOptions(cluster, kthfsTemplate, initScript);

            for (NodeGroup group : cluster.getNodes()) {

                progressEJB.initializeCreateGroup(group.getServices().get(0), group.getNumber());

                messages.addMessage("Creating " + group.getNumber()
                        + "  nodes in Security Group " + group.getServices().get(0));
                Set<? extends NodeMetadata> ready = service.createNodesInGroup(
                        group.getServices().get(0), group.getNumber(), kthfsTemplate.build());

                //We keep track of the returned set of node Metadata launched and which group 
                messages.addMessage("Nodes created in Security Group " + group.getServices().get(0) + " with "
                        + "basic setup");
                nodes.put(group.getServices().get(0), ready);

                //Identify the biggest group
                max = max < group.getNumber() ? group.getNumber() : max;
                //Fetch the total of nodes
                totalNodes += group.getNumber();

                //Fetch the nodes info so we can launch first mgm before the rest!

                List<String> services = new LinkedList();
                services.addAll(group.getServices());

                Iterator<? extends NodeMetadata> iter = ready.iterator();

                int i = 0;
                while (iter.hasNext()) {
                    NodeMetadata node = iter.next();

                    if (services.contains("ndb::mgm")) {
                        //Add private ip to mgm
                        mgmIP.addAll(node.getPrivateAddresses());
                        List<String> mgmRecipes = new LinkedList();
                        mgmRecipes.addAll(group.getServices());
                        mgmRecipes.remove("ndb::mysqld");
                        mgmRecipes.remove("ndb::dn");
                        mgms.put(node, mgmRecipes);
                        //filter out mysqld or dn, will be executed later
                    }
                    if (services.contains("ndb::dn")) {

                        ndbsIP.addAll(node.getPrivateAddresses());
                        List<String> ndbRecipes = new LinkedList();
                        ndbRecipes.addAll(group.getServices());
                        ndbRecipes.remove("ndb::mgm");
                        ndbRecipes.remove("ndb::mysqld");
                        ndbs.put(node, ndbRecipes);
                        //idem, filter out other mysql cluster conflicts

                    }
                    if (services.contains("ndb::mysqld")) {

                        mySQLClientsIP.addAll(node.getPrivateAddresses());
//                        //To avoid launching the node in two phases, see if it is sharing a same node

                        //For a shared node with mgm, it will run 2 times on it.
                        List<String> mysqldRecipes = new LinkedList();
                        mysqldRecipes.addAll(group.getServices());
                        mysqldRecipes.remove("ndb::mgm");
                        mysqldRecipes.remove("ndb::dn");
                        mysqlds.put(node, mysqldRecipes);
                        //filter out other mysql conflicts

                    }
                    if (services.contains("hop::namenode")) {

                        namenodesIP.addAll(node.getPrivateAddresses());
                        //avoid relaunching a node in the corresponding 
                        namenodes.put(node, services);
                        //filter out conflicts
                    }

                    if (services.contains("hop::datanode")) {

                        datanodes.put(node, services);

                    }
                    //update entry on the progression
                    Host host = new Host();
                    if (node != null) {
                        host.setHostname(node.getHostname());
                        if (node.getPrivateAddresses().iterator().hasNext()) {
                            host.setPrivateIp(node.getPrivateAddresses().iterator().next());
                        }
                        if (node.getPublicAddresses().iterator().hasNext()) {
                            host.setPublicIp(node.getPublicAddresses().iterator().next());
                        }
                        String nodeId = node.getId();
                        host.setHostId(nodeId.replaceFirst("/", "-"));

                        hostEJB.storeHost(host, true);
                    }
                    progressEJB.updateCreateProgress(group.getServices().get(0), i++, node);
                }

            }
            status = true;
        } catch (RunNodesException e) {
            System.out.println("error adding nodes to group "
                    + "ups something got wrong on the nodes");
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
        } finally {
            return status;
        }
    }

    /*
     * 
     * This is the based on the procedure we do for baremetal but without the jclouds API, fails due to 
     * limitations on POST requests
     *  @alpha version
     */
    public boolean ParallellaunchNodesBasicSetup() {
        boolean status = true;

        //Pending groups
//        ConcurrentHashMap<String, NodeGroup> pendingGroups =
//                new ConcurrentHashMap<String, NodeGroup>();
        CopyOnWriteArraySet<NodeGroup> pendingGroups = new CopyOnWriteArraySet<NodeGroup>();
        latch = new CountDownLatch(cluster.getNodes().size());
        pool = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(cluster.getNodes().size()));
        final TemplateBuilder kthfsTemplate = templateKTHFS(cluster, service.templateBuilder());
        //Use better Our scriptbuilder abstraction
        ScriptBuilder initScript = ScriptBuilder.builder()
                .scriptType(ScriptBuilder.ScriptType.INIT)
                .publicKey(publicKey)
                .gitRepo(cluster.getGlobal().getGit().getRepository())
                .build();

        selectProviderTemplateOptions(cluster, kthfsTemplate, initScript);
        for (final NodeGroup group : cluster.getNodes()) {
            try {
                progressEJB.initializeCreateGroup(group.getServices().get(0), group.getNumber());
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println("Error updating the DataBase");
            }
            messages.addMessage("Creating " + group.getNumber()
                    + "  nodes in Security Group " + group.getServices().get(0));
            max = max < group.getNumber() ? group.getNumber() : max;
            //Fetch the total of nodes
            totalNodes += group.getNumber();


            List<String> services = new LinkedList<String>();
            services.addAll(group.getServices());

            final StoreResults results = new StoreResults(services, latch, this);
            //Create async provision
            //Generate listenable future that will store the results in the hashmap when done
            ListenableFuture<Set<? extends NodeMetadata>> groupCreation =
                    pool.submit(new CreateGroupCallable(service, group, kthfsTemplate,
                    nodes, messages, pendingGroups));
            //Generate function to store results when done
            Futures.transform(groupCreation, results);
            try {
                Thread.sleep(group.getNumber() * 3500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }


        try {
            latch.await(totalNodes * 30, TimeUnit.MINUTES);

            //check inconsistencies, retry problematic groups
            if (!pendingGroups.isEmpty()) {
                //retry inconsistent group of nodes
                status = retryGroupCreation(service, kthfsTemplate, pool, latch,
                        messages, nodes, pendingGroups, RETRIES);
            }
            if (status) {
                //update that all the nodes have been completed
                for (String group : nodes.keySet()) {
                    Set<? extends NodeMetadata> createdGroup = nodes.get(group);
                    int i = 0;
                    for (NodeMetadata node : createdGroup) {
                        Host host = new Host();
                        if (node != null) {
                            host.setHostname(node.getHostname());
                            if (node.getPrivateAddresses().iterator().hasNext()) {
                                host.setPrivateIp(node.getPrivateAddresses().iterator().next());
                            }
                            if (node.getPublicAddresses().iterator().hasNext()) {
                                host.setPublicIp(node.getPublicAddresses().iterator().next());
                            }
                            String nodeId = node.getId();
                            host.setHostId(nodeId.replaceFirst("/", "-"));

                            hostEJB.storeHost(host, true);
                        }
                        progressEJB.updateCreateProgress(group, i++, node);
                    }
                }
            }
        } catch (InterruptedException e) {
            System.out.println("Failed to create the VMS");
            status = false;
        } finally {
            return status;
        }
    }
    /*
     * Mechanism to retry failures during parallel creation of nodes.
     */

    private boolean retryGroupCreation(ComputeService service, TemplateBuilder kthfsTemplate,
            ListeningExecutorService pool, CountDownLatch latch, MessageController messages,
            Map<String, Set<? extends NodeMetadata>> nodes,
            CopyOnWriteArraySet<NodeGroup> pending, int times) {
        boolean success = false;
        int retries = times;
        while (retries != 0 && !pending.isEmpty()) {
            latch = new CountDownLatch(pending.size());


            for (NodeGroup group : pending) {
                List<String> services = new LinkedList<String>();
                services.addAll(group.getServices());

                final StoreResults results = new StoreResults(services, latch, this);

                //Launch purge and create async process, it will purge phantom nodes of the group and
                //later request for new machines
                ListenableFuture<Set<? extends NodeMetadata>> groupCreation =
                        pool.submit(new PurgeCreateGroupCallable(service, group,
                        kthfsTemplate, nodes, messages, pending));
                //Generate function to store results when done
                Futures.transform(groupCreation, results);
                try {
                    Thread.sleep(group.getNumber() * 3500);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            try {
                latch.await(pending.size() * 30, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
            } finally {
                success = true;
            }
        }
        return success;
    }

    /*
     * Method for the install phase
     * 
     */
    @Override
    public void installPhase() {
        //We specify a thread pool with the same number of nodes in the system and resources are
        //Total Nodes*2
        pool = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool((int) (totalNodes * 2)));
        ScriptBuilder.Builder scriptBuilder =
                ScriptBuilder.builder().scriptType(ScriptBuilder.ScriptType.INSTALL);
        Set<NodeMetadata> groupLaunch = new HashSet<NodeMetadata>(mgms.keySet());
        groupLaunch.addAll(ndbs.keySet());
        groupLaunch.addAll(mysqlds.keySet());
        groupLaunch.addAll(namenodes.keySet());
        groupLaunch.addAll(datanodes.keySet());
        messages.addMessage("Configuring installation phase in all nodes");
        messages.addMessage("Running install process of software");
        try {
            progressEJB.updatePhaseProgress(groupLaunch, DeploymentPhase.INSTALL);
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Error updating in the DataBase");
        }
        nodeInstall(groupLaunch, scriptBuilder, RETRIES);

    }

    /*
     * Method to setup the nodes in the correct order for our platform in the first run
     */
    @Override
    public void deployingConfigurations() {
        //create pool of threads taking the biggest cluster
        pool = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(max * 2));
        //latch = new CountDownLatch(mgmNodes.size());
        //First phase mgm configuration
        ScriptBuilder.Builder scriptBuilder = ScriptBuilder.builder()
                .mgms(mgmIP)
                .mysql(mySQLClientsIP)
                .namenodes(namenodesIP)
                .ndbs(ndbsIP)
                .privateIP(privateIP)
                .publicKey(publicKey)
                .clusterName(cluster.getName())
                .scriptType(ScriptBuilder.ScriptType.HOPS);

        //Asynchronous node launch
        //launch mgms
        Set<NodeMetadata> groupLaunch = mgms.keySet();
        messages.addMessage("Configuring mgm nodes");

        //Update view state to configure
        persistState(groupLaunch, DeploymentPhase.CONFIGURE);
        nodePhase(groupLaunch, mgms, scriptBuilder, RETRIES);


        //launch ndbs
        groupLaunch = ndbs.keySet();
        messages.addMessage("Configuring ndb nodes");

        persistState(groupLaunch, DeploymentPhase.CONFIGURE);
        nodePhase(groupLaunch, ndbs, scriptBuilder, RETRIES);


        //launch mysqlds
        groupLaunch = mysqlds.keySet();
        messages.addMessage("Configuring mysqld nodes");

        persistState(groupLaunch, DeploymentPhase.CONFIGURE);
        nodePhase(groupLaunch, mysqlds, scriptBuilder, RETRIES);


        //launch namenodes
        groupLaunch = namenodes.keySet();
        messages.addMessage("Configuring namenodes");

        persistState(groupLaunch, DeploymentPhase.CONFIGURE);
        nodePhase(groupLaunch, namenodes, scriptBuilder, RETRIES);


        //launch datanodes
        groupLaunch = datanodes.keySet();
        messages.addMessage("Configuring datanodes");

        persistState(groupLaunch, DeploymentPhase.CONFIGURE);
        nodePhase(groupLaunch, datanodes, scriptBuilder, RETRIES);

    }

    @Override
    public List<String> getNdbsIP() {
        return ndbsIP;
    }

    @Override
    public List<String> getMgmIP() {
        return mgmIP;
    }

    @Override
    public List<String> getMySQLClientIP() {
        return mySQLClientsIP;
    }

    @Override
    public List<String> getNamenodesIP() {
        return namenodesIP;
    }

    @Override
    public Map<NodeMetadata, List<String>> getMgms() {
        return mgms;
    }

    @Override
    public Map<NodeMetadata, List<String>> getNdbs() {
        return ndbs;
    }

    @Override
    public Map<NodeMetadata, List<String>> getMysqlds() {
        return mysqlds;
    }

    @Override
    public Map<NodeMetadata, List<String>> getNamenodes() {
        return namenodes;
    }

    @Override
    public Map<NodeMetadata, List<String>> getDatanodes() {
        return datanodes;
    }

    /*
     * Private methods for the virtualizer
     */
    private ComputeService initContext() {

        //We define the properties of our service
        Properties serviceDetails = serviceProperties();

        // example of injecting a ssh implementation
        // injecting the logging module
        Iterable<Module> modules = ImmutableSet.<Module>of(
                new SshjSshClientModule(),
                new SLF4JLoggingModule(),
                new EnterpriseConfigurationModule());

        ContextBuilder build = null;
        //We prepare the context depending of what the user selects
        switch (provider) {
            case AWS_EC2:
                build = ContextBuilder.newBuilder(provider.toString())
                        .credentials(id, key)
                        .modules(modules)
                        .overrides(serviceDetails);

                break;
            case OPENSTACK:
                build = ContextBuilder.newBuilder(provider.toString())
                        .endpoint(endpoint)
                        .credentials(id, key)
                        .modules(modules)
                        .overrides(serviceDetails);

                break;
            //Rackspace not implemented,
            case RACKSPACE:
                build = ContextBuilder.newBuilder(provider.toString())
                        .credentials(id, key)
                        .modules(modules)
                        .overrides(serviceDetails);
                break;
        }

        if (build == null) {
            throw new NullPointerException("Not selected supported provider");



        }

        ComputeServiceContext context = build.buildView(ComputeServiceContext.class);

        //From minecraft example, how to include your own event handlers
        context.utils()
                .eventBus().register(ProvisionController.ScriptLogger.INSTANCE);
        messages.addMessage(
                "Virtualization context initialized, start opening security groups");
        return context.getComputeService();
    }

    /*
     * Define the service properties for the compute service context using
     * Amazon EC2 like Query parameters and regions. 
     * Does the same for Openstack and Rackspace but we dont setup anything for now
     * 
     * Includes time using the ports when launching the VM instance executing the script
     */
    private Properties serviceProperties() {
        Properties properties = new Properties();
        long scriptTimeout = TimeUnit.MILLISECONDS.convert(50, TimeUnit.MINUTES);
        properties.setProperty(TIMEOUT_SCRIPT_COMPLETE, scriptTimeout + "");
        properties.setProperty(TIMEOUT_PORT_OPEN, scriptTimeout + "");
        properties.setProperty(PROPERTY_CONNECTION_TIMEOUT, scriptTimeout + "");

        switch (provider) {
            case AWS_EC2:
                properties.setProperty(PROPERTY_EC2_AMI_QUERY, "owner-id=137112412989;state=available;image-type=machine");
                properties.setProperty(PROPERTY_EC2_CC_AMI_QUERY, "");

                break;
            case OPENSTACK:
                break;
            case RACKSPACE:
                break;
        }
        return properties;
    }

    /*
     * Select extra options depending of the provider we selected
     * For example we include the bootstrap script to download and do basic setup the first time
     * For openstack we override the need to generate a key pair and the user used by the image to login
     * EC2 jclouds detects the login by default
     */
    private void selectProviderTemplateOptions(Cluster cluster, TemplateBuilder kthfsTemplate,
            ScriptBuilder script) {

        StatementList bootstrap = new StatementList(script);
        switch (provider) {
            case AWS_EC2:
                if (!cluster.getProvider().getLoginUser().equals("")) {
                    kthfsTemplate.options(EC2TemplateOptions.Builder
                            .runScript(bootstrap).overrideLoginUser(cluster.getProvider().getLoginUser()));
                } else {
                    kthfsTemplate.options(EC2TemplateOptions.Builder
                            .runScript(bootstrap));
                }
                break;
            case OPENSTACK:
                kthfsTemplate.options(NovaTemplateOptions.Builder
                        .overrideLoginUser(cluster.getProvider().getLoginUser())
                        .generateKeyPair(true)
                        .runScript(bootstrap));
                break;
            case RACKSPACE:

                break;
            default:
                throw new AssertionError();
        }
    }

    /*
     * Template of the VM we want to launch using EC2, or Openstack
     */
    private TemplateBuilder templateKTHFS(Cluster cluster, TemplateBuilder template) {

        switch (provider) {
            case AWS_EC2:
                template.os64Bit(true);
                template.hardwareId(cluster.getProvider().getInstanceType());
                template.imageId(cluster.getProvider().getImage());
                template.locationId(cluster.getProvider().getRegion());
                break;
            case OPENSTACK:
                template.os64Bit(true);
                template.imageId(cluster.getProvider().getRegion()
                        + "/" + cluster.getProvider().getImage());
                template.hardwareId(cluster.getProvider().getRegion()
                        + "/" + cluster.getProvider().getInstanceType());
                break;
            case RACKSPACE:
                break;
            default:
                throw new AssertionError();
        }


        return template;
    }

    private void nodePhase(Set<NodeMetadata> nodes, Map<NodeMetadata, List<String>> map,
            ScriptBuilder.Builder scriptBuilder, int times) {
        //Iterative Approach
        int retries = times;
        pendingNodes = new CopyOnWriteArraySet<NodeMetadata>(nodes);
        while (!pendingNodes.isEmpty() && retries != 0) {
            latch = new CountDownLatch(pendingNodes.size());
            Iterator<NodeMetadata> iter = pendingNodes.iterator();
            while (iter.hasNext()) {
                final NodeMetadata node = iter.next();
                System.out.println(node.toString());
                List<String> ips = new LinkedList(node.getPrivateAddresses());
                //Listenable Future
                String nodeId = node.getId();
                ScriptBuilder script = scriptBuilder.build(ips.get(0), map.get(node), nodeId.replaceFirst("/", "-"));
                ListenableFuture<ExecResponse> future = service.submitScriptOnNode(node.getId(), new StatementList(script),
                        RunScriptOptions.Builder.overrideAuthenticateSudo(true).overrideLoginCredentials(node.getCredentials()));
                future.addListener(new NodeStatusTracker(node, latch, pendingNodes,
                        future), pool);
            }
            try {
                //wait for all the works to finish, 25 min estimated for each node +30 min extra margin to give
                //some extra time.
                latch.await(25 * nodes.size() + 60, TimeUnit.MINUTES);
                messages.addMessage("Launch phase complete...");

            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                //Update the nodes that have finished the install phase
                Set<NodeMetadata> complete = new HashSet<NodeMetadata>(nodes);
                if (!pendingNodes.isEmpty()) {

                    Set<NodeMetadata> remain = new HashSet<NodeMetadata>(pendingNodes);
                    //Mark the nodes that are been reinstalled and completed
                    complete.removeAll(remain);
                    persistState(complete, DeploymentPhase.COMPLETE);
                    persistState(remain, DeploymentPhase.RETRYING);
                    System.out.println("Retrying");
                    --retries;
                } else {
                    persistState(complete, DeploymentPhase.COMPLETE);
                }
            }
        }
        if (retries == 0 && !pendingNodes.isEmpty()) {
            persistState(pendingNodes, DeploymentPhase.ERROR);
        }
    }

    private void nodeInstall(Set<NodeMetadata> nodes, ScriptBuilder.Builder scriptBuilder, int times) {
        //Iterative Approach
        int retries = times;
        pendingNodes = new CopyOnWriteArraySet<NodeMetadata>(nodes);
        while (retries != 0 && !pendingNodes.isEmpty()) {
            //Initialize countdown latch
            latch = new CountDownLatch(pendingNodes.size());
            Iterator<NodeMetadata> iter = pendingNodes.iterator();
            while (iter.hasNext()) {
                final NodeMetadata node = iter.next();
                //Listenable Future
                ScriptBuilder script = scriptBuilder.build();
                ListenableFuture<ExecResponse> future = service.submitScriptOnNode(
                        node.getId(),
                        new StatementList(script),
                        RunScriptOptions.Builder.overrideAuthenticateSudo(true)
                        .overrideLoginCredentials(node.getCredentials()));
//              
                future.addListener(new NodeStatusTracker(node, latch, pendingNodes,
                        future), pool);
            }
            try {
                //wait for all the works to finish, 25 min estimated for each node +30 min extra margin to give
                //some extra time.
                latch.await(25 * nodes.size() + 60, TimeUnit.MINUTES);
                messages.addMessage("Install phase complete...");

            } catch (InterruptedException e) {

                e.printStackTrace();
            } finally {
                //Update the nodes that have finished the install phase
                Set<NodeMetadata> complete = new HashSet<NodeMetadata>(nodes);

                if (!pendingNodes.isEmpty()) {
                    Set<NodeMetadata> remain = new HashSet<NodeMetadata>(pendingNodes);
                    //Mark the nodes that are been reinstalled and completed
                    complete.removeAll(remain);
                    persistState(complete, DeploymentPhase.WAITING);
                    persistState(remain, DeploymentPhase.RETRYING);
                    --retries;
                    System.out.println("Retrying Nodes in Install phase");
                } else {
                    persistState(complete, DeploymentPhase.WAITING);
                }
            }
        }
        if (retries == 0 && !pendingNodes.isEmpty()) {
            persistState(pendingNodes, DeploymentPhase.ERROR);
        }
    }

    /*
     * Persist state
     */
    private void persistState(Set<NodeMetadata> groupLaunch, DeploymentPhase phase) {
        try {
            progressEJB.updatePhaseProgress(groupLaunch, phase);
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Error Updating Database");
        }
    }
}
