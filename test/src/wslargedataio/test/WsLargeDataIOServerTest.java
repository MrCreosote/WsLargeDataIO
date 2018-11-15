package wslargedataio.test;

import java.io.File;
import java.net.URL;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

import junit.framework.Assert;

import org.apache.commons.io.FileUtils;
import org.ini4j.Ini;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import wslargedataio.GetObjectsParams;
import wslargedataio.ObjectSaveData;
import wslargedataio.ObjectSpecification;
import wslargedataio.SaveObjectsParams;
import wslargedataio.WsLargeDataIOServer;
import us.kbase.auth.AuthConfig;
import us.kbase.auth.AuthToken;
import us.kbase.auth.AuthService;
import us.kbase.auth.ConfigurableAuthService;
import us.kbase.common.service.JsonServerSyslog;
import us.kbase.common.service.RpcContext;
import us.kbase.common.service.UObject;
import us.kbase.workspace.CreateWorkspaceParams;
import us.kbase.workspace.GetObjects2Params;
import us.kbase.workspace.ProvenanceAction;
import us.kbase.workspace.WorkspaceClient;
import us.kbase.workspace.WorkspaceIdentity;

public class WsLargeDataIOServerTest {
    private static AuthToken token = null;
    private static Map<String, String> config = null;
    private static WorkspaceClient wsClient = null;
    private static String wsName = null;
    private static WsLargeDataIOServer impl = null;
    
    @BeforeClass
    public static void init() throws Exception {
        String configFilePath = System.getenv("KB_DEPLOYMENT_CONFIG");
        File deploy = new File(configFilePath);
        Ini ini = new Ini(deploy);
        config = ini.get("WsLargeDataIO");

        // Token validation
        String authUrl = config.get("auth-service-url");
        String authUrlInsecure = config.get("auth-service-url-allow-insecure");
        ConfigurableAuthService authService = new ConfigurableAuthService(
                new AuthConfig().withKBaseAuthServerURL(new URL(authUrl))
                        .withAllowInsecureURLs("true".equals(authUrlInsecure)));
        token = authService.validateToken(System.getenv("KB_AUTH_TOKEN"));
        wsClient = new WorkspaceClient(new URL(config.get("workspace-url")), token);
        wsClient.setIsInsecureHttpConnectionAllowed(true);

        // These lines are necessary because we don't want to start linux syslog bridge service
        JsonServerSyslog.setStaticUseSyslog(false);
        JsonServerSyslog.setStaticMlogFile(new File(config.get("scratch"), "test.log").getAbsolutePath());
        impl = new WsLargeDataIOServer();
    }
    
    private static String getWsName() throws Exception {
        if (wsName == null) {
            long suffix = System.currentTimeMillis();
            wsName = "test_WsLargeDataIO_" + suffix;
            wsClient.createWorkspace(new CreateWorkspaceParams().withWorkspace(wsName));
        }
        return wsName;
    }
    
    private static RpcContext getContext() {
        return new RpcContext().withProvenance(Arrays.asList(new ProvenanceAction()
            .withService("WsLargeDataIO").withMethod("please_never_use_it_in_production")
            .withMethodParams(new ArrayList<UObject>())));
    }
    
    @AfterClass
    public static void cleanup() {
        if (wsName != null) {
            try {
                wsClient.deleteWorkspace(new WorkspaceIdentity().withWorkspace(wsName));
                System.out.println("Test workspace was deleted");
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }
    
    @SuppressWarnings("unchecked")
    @Test
    public void testSaveAndGetObjects() throws Exception {
        File tempFile = new File(config.get("scratch"), "test.json");
        Map<String, Object> taxon = new TreeMap<>();
        taxon.put("taxonomy_id", 0);
        taxon.put("domain", "localhost");
        taxon.put("scientific_name", "Fakus Objectus");
        FileUtils.writeStringToFile(tempFile, UObject.transformObjectToString(taxon));
        String taxonObjName = "taxon.1";
        Object ret = impl.saveObjects(new SaveObjectsParams().withWorkspace(getWsName())
                .withObjects(Arrays.asList(new ObjectSaveData()
                .withType("KBaseGenomeAnnotations.Taxon").withName(taxonObjName)
                .withDataJsonFile(tempFile.getAbsolutePath()))), token, getContext());
        Assert.assertNotNull(ret);
        File targetFile = new File(impl.getObjects(new GetObjectsParams().withObjects(
                Arrays.asList(new ObjectSpecification().withRef(
                        getWsName() + "/" + taxonObjName))), token, getContext())
                .getData().get(0).getDataJsonFile());
        Map<String, Object> obj = UObject.getMapper().readValue(targetFile, Map.class);
        Assert.assertEquals(UObject.transformObjectToString(taxon),
                UObject.transformObjectToString(new TreeMap<>(obj)));
        
        /* check provenance is saved correctly
           just checks that the provenance set in the callback server by the
           test runner is there, that means the provenance has been correctly
           pulled from the callback server and saved with the object
         */
        final ProvenanceAction prov = wsClient.getObjects2(
                new GetObjects2Params()
                    .withNoData(1L)
                    .withObjects(Arrays.asList(
                            new us.kbase.workspace.ObjectSpecification()
                                .withRef(getWsName() + "/" + taxonObjName))))
                .getData().get(0).getProvenance().get(0);
        Assert.assertEquals(
                "KBase SDK method run via the KBase Execution Engine",
                prov.getDescription());
        Assert.assertEquals("WsLargeDataIO", prov.getService());
    }
}
