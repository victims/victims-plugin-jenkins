package com.redhat.victims.plugin.jenkins;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.tools.ant.types.resources.LogOutputResource;
import org.kohsuke.stapler.DataBoundConstructor;

import com.redhat.victims.VictimsConfig;
import com.redhat.victims.VictimsException;
import com.redhat.victims.VictimsResultCache;
import com.redhat.victims.database.VictimsDB;
import com.redhat.victims.database.VictimsDBInterface;

import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.ListBoxModel;

public class VictimsPostBuildScanner extends Recorder {

    private String baseUrl      = Settings.BASE_URL_DEFAULT;
    private String entryPoint   = Settings.ENTRY_DEFAULT;
    private String metadata     = Settings.MODE_WARNING;
    private String fingerprint  = Settings.MODE_WARNING;
    private String updates      = Settings.UPDATES_AUTO;
    private String jdbcDriver   = VictimsDB.defaultDriver();
    private String jdbcUrl      = "";
    private String jdbcUsername = Settings.USER_DEFAULT;
    private String jdbcPassword = Settings.PASS_DEFAULT;
    private String outputDir    = System.getenv("BUILD_URL"); // Environment var set by jenkins
    
    public ExecutionContext ctx;

    @DataBoundConstructor
    public VictimsPostBuildScanner(final String baseUrl,
                                    final String entryPoint, final String metadata,
                                    final String fingerprint, final String updates,
                                    final String jdbcDriver, final String jdbcUrl,
                                    final String jdbcUsername, final String jdbcPassword,
                                    final String outputDir) {
        setBaseUrl(baseUrl);
        setEntryPoint(entryPoint);
        setMetadata(metadata);
        setFingerprint(fingerprint);
        setUpdates(updates);
        setJdbcDriver(jdbcDriver);
        setJdbcUrl(jdbcUrl);
        setJdbcUsername(jdbcUsername);
        setJdbcPassword(jdbcPassword);
        setOutputDir(outputDir);
    }

    // Descriptor Implementation
    public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Scan Project Dependencies for Vulnerabilities";
        }

        public ListBoxModel doFillMetadataItems() {
            ListBoxModel items = new ListBoxModel();
            for (String wt : Settings.ListModes()) {
                items.add(wt.substring(0, 1).toUpperCase() + wt.substring(1), wt);
            }
            return items;
        }

        public ListBoxModel doFillFingerprintItems() {
            return doFillMetadataItems();
        }

        public ListBoxModel doFillUpdatesItems() {
            ListBoxModel items = new ListBoxModel();
            for (String up : Settings.ListUpdates()) {
                items.add(up.substring(0, 1).toUpperCase() + up.substring(1), up);
            }
            return items;
        }

        public String getDefaultBaseUrl() {
            return Settings.BASE_URL_DEFAULT;
        }

        public String getDefaultEntryPoint() {
            return Settings.ENTRY_DEFAULT;
        }

        public String getDefaultJdbcDriver() {
            return VictimsDB.defaultDriver();
        }

        /* Currently not working (dependency clash?)
        public String getDefaultJdbcUrl() {
            return VictimsDB.defaultURL();
        }
        */

        public String getDefaultJdbcUsername() {
            return Settings.USER_DEFAULT;
        }

        public String getDefaultJdbcPassword() {
            return Settings.PASS_DEFAULT;
        }
        
        public String getDefaultOutputDir() {
            return System.getenv("BUILD_URL");
        }
    }

    @Extension
    public static final DescriptorImpl Descriptor = new DescriptorImpl();

    @Override
    public BuildStepDescriptor<Publisher> getDescriptor() {
        return Descriptor;
    }

    public BuildStepMonitor getRequiredMonitorService() {
        // No synchronisation necessary between concurrent builds
        return BuildStepMonitor.NONE;
    }

    // Function that is run when project is built
    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws IOException {
        if (!setupExecutionContext(listener.getLogger())) {
            // Returning false has been deprecated, throw exception instead.
            throw new AbortException();
        }
        
        return true;
    }

    public boolean setupExecutionContext(PrintStream log) {
        ctx = new ExecutionContext();
        ctx.setSettings(new Settings());
        ctx.setLog(log);
        
        ctx.getSettings().set(VictimsConfig.Key.URI, baseUrl);
        ctx.getSettings().set(VictimsConfig.Key.DB_DRIVER, jdbcDriver);
        ctx.getSettings().set(VictimsConfig.Key.DB_URL, jdbcUrl);
        ctx.getSettings().set(Settings.METADATA, metadata);
        ctx.getSettings().set(Settings.FINGERPRINT, fingerprint);
        ctx.getSettings().set(VictimsConfig.Key.ENTRY, entryPoint);
        ctx.getSettings().set(VictimsConfig.Key.DB_USER, jdbcUsername);
        ctx.getSettings().set(VictimsConfig.Key.DB_PASS, jdbcPassword);
        ctx.getSettings().set(Settings.UPDATE_DATABASE, updates);
        
        System.setProperty(VictimsConfig.Key.ALGORITHMS, "SHA512");
        
        try {
            VictimsResultCache cache = new VictimsResultCache();
            ctx.setCache(cache);
            
            VictimsDBInterface db = VictimsDB.db();
            ctx.setDatabase(db);
            
            ctx.getSettings().validate();
            ctx.getSettings().show(ctx.getLog());
        } catch (VictimsException e) {
            log.println("[VICTIMS] ERROR:");
            log.println(e.getMessage());
            return false;
        }
        
        return true;
    }
    
    /**
     * Creates and synchronises the database then checks supplied dependencies
     * against the vulnerability database.
     */
    public void execute()
    {
        
    }
    
    public boolean doStuff(AbstractBuild<?, ?> build, PrintStream logger) throws IOException {
        
        
        FilePath projectWorkspace = build.getWorkspace();

        DateFormat dateFormat = new SimpleDateFormat("mm_ss");
        Date date = new Date();

        String newFile = projectWorkspace + "/" + dateFormat.format(date)
                + ".txt";

        File f = new File(newFile);

        long check = FileUtils.checksumCRC32(f);
        
        if (!f.exists()) {
            f.createNewFile();
        }

        logger.println("[VICTIMS] ------------------------------------------------------------------------");
        logger.println("[VICTIMS] Starting scan for vulnerable jars");
        logger.println("[VICTIMS] Config:");
        logger.println("[VICTIMS]	baseUrl : " + this.baseUrl);
        logger.println("[VICTIMS]	entryPoint : " + this.entryPoint);
        logger.println("[VICTIMS]	metadata : " + this.metadata);
        logger.println("[VICTIMS]	fingerprint : " + this.fingerprint);
        logger.println("[VICTIMS]	updates : " + this.updates);
        logger.println("[VICTIMS]	jdbcDriver : " + this.jdbcDriver);
        logger.println("[VICTIMS]	jdbcURL : " + this.jdbcUrl);
        logger.println("[VICTIMS]	jdbcUsername : " + this.jdbcUsername);
        logger.println("[VICTIMS]	jdbcPassword : " + this.jdbcPassword);
        logger.println("[VICTIMS] ------------------------------------------------------------------------");

        return true;
    }

    // Getters and Setters
    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(final String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getEntryPoint() {
        return entryPoint;
    }

    public void setEntryPoint(final String entryPoint) {
        this.entryPoint = entryPoint;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(final String metadata) {
        this.metadata = metadata;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(final String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getUpdates() {
        return updates;
    }

    public void setUpdates(final String updates) {
        this.updates = updates;
    }

    public String getJdbcDriver() {
        return jdbcDriver;
    }

    public void setJdbcDriver(final String jdbcDriver) {
        this.jdbcDriver = jdbcDriver;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(final String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public String getJdbcUsername() {
        return jdbcUsername;
    }

    public void setJdbcUsername(final String jdbcUsername) {
        this.jdbcUsername = jdbcUsername;
    }

    public String getJdbcPassword() {
        return jdbcPassword;
    }

    public void setJdbcPassword(final String jdbcPassword) {
        this.jdbcPassword = jdbcPassword;
    }
    
    public String getOutputDir() {
        return outputDir;
    }
    
    public void setOutputDir(final String outputDir) {
        this.outputDir = outputDir;
    }

}
