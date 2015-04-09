package com.thegrayfiles.acceptance;

import com.google.common.io.Files;
import com.thegrayfiles.Application;
import com.thegrayfiles.ApplicationConfig;
import com.thegrayfiles.builder.EsxConfigurationResourceBuilder;
import com.thegrayfiles.resource.PxeSessionRequestResource;
import com.thegrayfiles.resource.PxeSessionResource;
import com.thegrayfiles.resource.RootResource;
import com.thegrayfiles.service.PxeFileService;
import com.thegrayfiles.service.SyslogParserService;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.IntegrationTest;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.boot.test.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.integration.file.tail.FileTailingMessageProducerSupport;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextTestExecutionListener;
import org.springframework.test.context.testng.AbstractTestNGSpringContextTests;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.web.client.RestTemplate;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static com.thegrayfiles.builder.EsxConfigurationResourceBuilder.anEsxConfiguration;
import static com.thegrayfiles.builder.PxeSessionRequestResourceBuilder.aSessionRequest;
import static org.testng.Assert.*;


@SpringApplicationConfiguration(classes = Application.class)
@WebAppConfiguration
@IntegrationTest({"server.port:0"})
// See http://stackoverflow.com/questions/25537436/acceptance-testing-a-spring-boot-web-app-with-testng
@TestExecutionListeners(inheritListeners = false, listeners = {
        DependencyInjectionTestExecutionListener.class,
        DirtiesContextTestExecutionListener.class})
@Test
@TestPropertySource(properties = "tail.autoStartup = false")
public class PxeSessionTest extends AbstractTestNGSpringContextTests {

    @Value("${local.server.port}")
    private int serverPort;

    @Autowired
    private ApplicationConfig config;

    private RestTemplate template = new TestRestTemplate();

    @BeforeMethod
    public void setup() throws IOException {
        String pxePath = Files.createTempDir().getAbsolutePath();
        config.setPxePath(pxePath);
        String kickstartPath = Files.createTempDir().getAbsolutePath();
        config.setKickstartPath(kickstartPath);
    }

    @Test
    public void canFetchSessionDetails() {
        String macAddress = "00:1a:2b:3c:4d:5e";

        ResponseEntity<PxeSessionResource> createdSessionResponse = createPxeSession(macAddress, anEsxConfiguration());
        assertEquals(createdSessionResponse.getStatusCode().value(), 200);

        ResponseEntity<PxeSessionResource> fetchedSessionResponse = getPxeSessionByUuid(createdSessionResponse.getBody().getUuid());
        assertEquals(fetchedSessionResponse.getStatusCode().value(), 200);

        PxeSessionResource createdSession = createdSessionResponse.getBody();
        PxeSessionResource fetchedSession = fetchedSessionResponse.getBody();

        assertNotNull(fetchedSession, "Fetched session should not be null.");
        assertEquals(fetchedSession.getUuid(), createdSession.getUuid());
        assertEquals(fetchedSession.getMacAddress(), createdSession.getMacAddress());
    }

    @Test
    public void canFetchMultipleSessions() {
        String firstMacAddress = "00:1a:2b:3c:4d:5e";
        String secondMacAddress = "00:1a:2b:3c:4d:5f";

        ResponseEntity<PxeSessionResource> firstResponse = createPxeSession(firstMacAddress, anEsxConfiguration());
        ResponseEntity<PxeSessionResource> secondResponse = createPxeSession(secondMacAddress, anEsxConfiguration());

        List<PxeSessionResource> sessions = getPxeSessions();
        assertTrue(sessions.size() >= 2);
        assertTrue(sessions.contains(firstResponse.getBody()));
        assertTrue(sessions.contains(secondResponse.getBody()));
    }

    private List<PxeSessionResource> getPxeSessions() {
        return Arrays.asList(template.getForObject(getRootResource().getLink("session").getHref(), PxeSessionResource[].class));
    }

    @Test
    public void pxeSessionGeneratesMacConfigFile() throws IOException {
        String macAddress = "00:1a:2b:3c:4d:5e";
        String ip = "1.2.3.4";
        String password = "somsmgomsg";
        String macAddressFilename = "01-" + macAddress.replaceAll("[:]", "-");

        ResponseEntity<PxeSessionResource> session = createPxeSession(macAddress, anEsxConfiguration().withIp(ip).withPassword(password));
        assertEquals(session.getStatusCode().value(), 200);

        File macAddressFile = new File(config.getPxePath() + "/" + macAddressFilename);
        macAddressFile.deleteOnExit();
        assertTrue(macAddressFile.exists(), "Mac Address file " + macAddressFile.getAbsolutePath() + " should exist.");

        List<String> macAddressFileContent = FileUtils.readLines(macAddressFile);
        assertTrue(macAddressFileContent.get(0).startsWith("default menu.c32"),
                "Unexpected first line of MAC address file: " + macAddressFileContent.get(0));

        assertTrue(macAddressFileContent.get(macAddressFileContent.size() - 1).matches("^\\s+append.*?" + macAddress.replaceAll("[:]", "-") + "\\.cfg$"),
                "Unexpected last line of MAC address file: " + macAddressFileContent.get(macAddressFileContent.size() - 1));
    }

    @Test
    public void pxeSessionGeneratesKickstartFile() throws IOException {
        String macAddress = "00:11:22:33:44:55";
        String ip = "1.2.3.4";
        String netmask = "123.456.789.1";
        String gateway = "981.765.432.1";
        String password = "moo";
        String hostname = "somehost";
        String kickstartFilename = config.getKickstartPath() + "/" + macAddress.replaceAll("[:]", "-") + ".cfg";
        ResponseEntity<PxeSessionResource> session = createPxeSession(macAddress, anEsxConfiguration().withIp(ip).withPassword(password).withGateway(gateway).withNetmask(netmask).withHostname(hostname));
        assertEquals(session.getStatusCode().value(), 200);

        File kickstartFile = new File(kickstartFilename);
        kickstartFile.deleteOnExit();

        String kickstartFileContent = FileUtils.readFileToString(kickstartFile).replaceAll("[\\n\\r]", " ");

        assertTrue(kickstartFile.exists(), "Kickstart file " + kickstartFile.getAbsolutePath() + " should exist.");
        assertTrue(kickstartFileContent.startsWith("accepteula"),
                "Unexpected kickstart file content: " + kickstartFileContent);
        assertTrue(kickstartFileContent.matches(".*?rootpw " + password + ".*"), "Root password should be set to " + password);
        assertTrue(kickstartFileContent.matches(".*?network.*?--ip=" + ip + ".*"), "Kickstart should contain --ip=" + ip);
        assertTrue(kickstartFileContent.matches(".*?network.*?--hostname=" + hostname + ".*"), "Kickstart should contain --hostname=" + hostname);
        assertTrue(kickstartFileContent.matches(".*?network.*?--gateway=" + gateway + ".*"), "Kickstart should contain --gateway=" + gateway);
        assertTrue(kickstartFileContent.matches(".*?network.*?--netmask=" + netmask + ".*"), "Kickstart should contain --netmask=" + netmask);
    }

    @Test
    public void canGeneratePxeConfigForSpecificVersionOfEsx() throws IOException {
        generatePxeConfigForEsxVersion("5.0");
        generatePxeConfigForEsxVersion("5.1");
        generatePxeConfigForEsxVersion("5.5");
    }

    private void generatePxeConfigForEsxVersion(String version) throws IOException {
        String macAddress = "00:11:22:33:44:55";
        ResponseEntity<PxeSessionResource> session = createPxeSession(macAddress, anEsxConfiguration().withVersion(version));
        assertEquals(session.getStatusCode().value(), 200);

        String macAddressFilename = "01-" + macAddress.replaceAll("[:]", "-");
        File macAddressFile = new File(config.getPxePath() + "/" + macAddressFilename);
        macAddressFile.deleteOnExit();

        String content = FileUtils.readFileToString(macAddressFile).replaceAll("[\\n\\r]", " ");
        assertTrue(content.matches(".*?menu.*?" + version + ".*?kernel.*"), "Expected ESX version in menu line.");
        assertTrue(content.matches(".*?kernel.*?esxi-" + version + ".*?append.*"), "Expected ESX version in kernel line.");
        assertTrue(content.matches(".*?append.*?esxi-" + version + ".*"), "Expected ESX version in append line.");
    }

    private ResponseEntity<PxeSessionResource> createPxeSession(String macAddress, EsxConfigurationResourceBuilder esxConfigBuilder) {
        PxeSessionRequestResource request = aSessionRequest(macAddress).withEsxConfiguration(esxConfigBuilder).build();
        return template.postForEntity(getSessionHref(), request, PxeSessionResource.class);
    }

    private ResponseEntity<PxeSessionResource> getPxeSessionByUuid(String uuid) {
        return template.getForEntity(getRootResource().getLink("session").getHref() + "/" + uuid, PxeSessionResource.class);
    }

    private String getSessionHref() {
        return getRootResource().getLink("session").getHref();
    }

    private RootResource getRootResource() {
        String url = "http://127.0.0.1:" + serverPort;
        return template.getForEntity(url, RootResource.class).getBody();
    }

    @Test
    public void canDeleteSession() {
        String macAddress = "00:11:22:33:44:55";
        ResponseEntity<PxeSessionResource> createdSession = createPxeSession(macAddress, anEsxConfiguration());

        deletePxeSession(createdSession.getBody());

        assertEquals(getPxeSessionByUuid(createdSession.getBody().getUuid()).getStatusCode(), HttpStatus.NOT_FOUND);

        String macAddressFilename = "01-" + macAddress.replaceAll("[:]", "-");
        File macAddressFile = new File(config.getPxePath() + "/" + macAddressFilename);
        macAddressFile.deleteOnExit();
        assertFalse(macAddressFile.exists(), "Mac Address file should not exist.");
    }

    private void deletePxeSession(PxeSessionResource resource) {
        template.delete(getRootResource().getLink("session").getHref() + "/" + resource.getUuid());
    }

    @Test(timeOut = 10 * 1000)
    public void configFilesDeletedWhenSyslogUpdatedWithMacAddress() throws IOException {
        assertConfigFilesDeletedWhenSyslogUpdatedForEsxVersion("5.0");
        assertConfigFilesDeletedWhenSyslogUpdatedForEsxVersion("5.1");
        assertConfigFilesDeletedWhenSyslogUpdatedForEsxVersion("5.5");
    }

    private void assertConfigFilesDeletedWhenSyslogUpdatedForEsxVersion(final String version) throws IOException {
        SpringIntegrationHelper helper = new SpringIntegrationHelper(version);
        helper.verifyAssertions();
    }

    @Autowired
    private FileTailingMessageProducerSupport tailer;

    @Autowired
    private SyslogParserService parserService;

    class SpringIntegrationHelper implements Runnable {

        private File syslogFile;
        private String version;
        private boolean kickstartFileExists = false;
        private boolean macAddressFileExists = true;
        private HttpStatus pxeSessionStatusCode = HttpStatus.OK;

        SpringIntegrationHelper(String version) throws IOException {
            this.syslogFile = File.createTempFile("sys", "log");
            this.version = version;
            tailer.setFile(syslogFile);
            tailer.start();
            this.run();
            tailer.stop();
        }

        @Override
        public void run() {
            String macAddress = "00:1a:2b:3c:4d:5" + version.replaceAll("^\\d\\.(\\d).*$", "$1");
            String ip = "1.2.3.4";
            String password = "something";
            String macAddressFilename = "01-" + macAddress.replaceAll("[:]", "-");
            String kickstartFilename = config.getKickstartPath() + "/" + macAddress.replaceAll("[:]", "-") + ".cfg";

            ResponseEntity<PxeSessionResource> session = createPxeSession(macAddress, anEsxConfiguration().withIp(ip).withPassword(password));
            assertEquals(session.getStatusCode().value(), 200);

            File kickstartFile = new File(kickstartFilename);
            File macAddressFile = new File(config.getPxePath() + "/" + macAddressFilename);
            assertTrue(macAddressFile.exists(), "Mac Address file " + macAddressFile.getAbsolutePath() + " should exist.");

            String macAddressSyslog = "Mar 15 11:41:49 pxe in.tftpd[7034]: RRQ from 10.100.12.178 filename pxe/pxelinux.cfg/" + macAddressFilename + "\n";
            String toolsSyslog = "Mar 15 11:41:49 pxe in.tftpd[7034]: RRQ from 10.100.12.178 filename pxe/esxi-" + version + "/tools.t00\n";
            try {
                while (!parserService.isMacAddressDetected()) {
                    FileUtils.writeStringToFile(syslogFile, macAddressSyslog);
                    Thread.sleep(500);
                }
                while (!parserService.isMacAddressFileRemoved()) {
                    FileUtils.writeStringToFile(syslogFile, toolsSyslog, true);
                    Thread.sleep(500);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            kickstartFileExists = kickstartFile.exists();
            macAddressFileExists = macAddressFile.exists();
            pxeSessionStatusCode = getPxeSessionByUuid(session.getBody().getUuid()).getStatusCode();
        }

        public void verifyAssertions() {
            assertTrue(kickstartFileExists, "Kickstart file should exist.");
            assertFalse(macAddressFileExists, "Mac Address file should not exist.");
            assertEquals(pxeSessionStatusCode, HttpStatus.NOT_FOUND, "PXE session should be removed when file is removed.");
        }
    }
}