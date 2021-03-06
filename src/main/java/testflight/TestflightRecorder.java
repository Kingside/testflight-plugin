package testflight;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.*;
import hudson.model.AbstractBuild;
import hudson.tasks.*;
import hudson.util.RunList;
import org.apache.commons.collections.Predicate;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.client.HttpClient;
import org.json.simple.parser.JSONParser;
import org.kohsuke.stapler.DataBoundConstructor;
import java.io.*;
import java.util.*;

import org.apache.commons.collections.CollectionUtils;

public class TestflightRecorder extends Recorder
{
    private String apiToken;
    private String teamToken;
    private Boolean notifyTeam;
    private String buildNotes;
    private String filePath;
    private String lists;
    private Boolean replace;
    @DataBoundConstructor
    public TestflightRecorder(String apiToken, String teamToken, Boolean notifyTeam, String buildNotes, String filePath, String lists, Boolean replace)
    {
        this.teamToken = teamToken;
        this.apiToken = apiToken;
        this.notifyTeam = notifyTeam;
        this.buildNotes = buildNotes;
        this.filePath = filePath;
        this.replace = replace;
        this.lists = lists;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    public BuildStepMonitor getRequiredMonitorService( )
    {
        return BuildStepMonitor.NONE;
    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener)
    {
        if (build.getResult().isWorseOrEqualTo(Result.FAILURE))
            return false;

        listener.getLogger().println("Uploading to testflight");

        try
        {
            EnvVars vars = build.getEnvironment(listener);

            File file = new File(vars.expand(filePath));
            listener.getLogger().println(file);

            HttpClient httpclient = new DefaultHttpClient();
            HttpPost httpPost = new HttpPost("http://testflightapp.com/api/builds.json");
            FileBody fileBody = new FileBody(file);

            MultipartEntity entity = new MultipartEntity();
            entity.addPart("api_token", new StringBody(apiToken));
            entity.addPart("team_token", new StringBody(teamToken));
            entity.addPart("notes", new StringBody(vars.expand(buildNotes)));
            entity.addPart("file", fileBody);
            if (lists.length() > 0)
                entity.addPart("distribution_lists", new StringBody(lists));
            entity.addPart("notify", new StringBody(notifyTeam ? "True" : "False"));
            entity.addPart("replace", new StringBody(replace ? "True" : "False"));
            httpPost.setEntity(entity);

            HttpResponse response = httpclient.execute(httpPost);
            HttpEntity resEntity = response.getEntity();

            InputStream is = resEntity.getContent();
            JSONParser parser = new JSONParser();

            final Map parsedMap = (Map)parser.parse(new BufferedReader(new InputStreamReader(is)));

            TestflightBuildAction installAction = new TestflightBuildAction();
            installAction.displayName = "Testflight Install Link";
            installAction.iconFileName = "package.gif";
            installAction.urlName = (String)parsedMap.get("install_url");
            build.addAction(installAction);

            TestflightBuildAction configureAction = new TestflightBuildAction();
            configureAction.displayName = "Testflight Configuration Link";
            configureAction.iconFileName = "gear2.gif";
            configureAction.urlName = (String)parsedMap.get("config_url");
            build.addAction(configureAction);
        }
        catch (Exception e)
        {
            listener.getLogger().println(e);
            return false;
        }

        return true;
    }

    @Override
    public Collection<? extends Action> getProjectActions(AbstractProject<?, ?> project)
    {
        ArrayList<TestflightBuildAction> actions = new ArrayList<TestflightBuildAction>();
        RunList<? extends AbstractBuild<?,?>> builds = project.getBuilds();

        Collection predicated = CollectionUtils.select(builds, new Predicate() {
            public boolean evaluate(Object o) {
                return ((AbstractBuild<?,?>)o).getResult().isBetterOrEqualTo(Result.SUCCESS);
            }
        });

        ArrayList<AbstractBuild<?,?>> filteredList = new ArrayList<AbstractBuild<?,?>>(predicated);


        Collections.reverse(filteredList);
        for (AbstractBuild<?,?> build : filteredList)
        {
           List<TestflightBuildAction> testflightActions = build.getActions(TestflightBuildAction.class);
           if (testflightActions != null && testflightActions.size() > 0)
           {
               for (TestflightBuildAction action : testflightActions)
               {
                   actions.add(new TestflightBuildAction(action));
               }
               break;
           }
        }

        return actions;
    }

    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher>
    {
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Upload to Testflight";
        }
    }
}
