/*
 * The MIT License
 * 
 * Copyright (c) 2011, Jesse Farinacci
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.jenkins.ci.plugins.jobimport;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import hudson.Extension;
import hudson.PluginManager;
import hudson.PluginWrapper;
import hudson.model.AbstractItem;
import hudson.model.AbstractProject;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.RootAction;
import hudson.model.TopLevelItem;
import hudson.security.ACL;
import hudson.security.Permission;
import hudson.security.PermissionGroup;
import hudson.security.PermissionScope;
import hudson.util.ListBoxModel;
import hudson.util.VersionNumber;
import jenkins.model.Jenkins;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.jenkins.ci.plugins.jobimport.client.RestApiClient;
import org.jenkins.ci.plugins.jobimport.model.JenkinsSite;
import org.jenkins.ci.plugins.jobimport.model.RemoteFolder;
import org.jenkins.ci.plugins.jobimport.model.RemoteItem;
import org.jenkins.ci.plugins.jobimport.utils.Constants;
import org.jenkins.ci.plugins.jobimport.utils.CredentialsUtils;
import org.jenkins.ci.plugins.jobimport.utils.CredentialsUtils.NullSafeCredentials;
import org.jenkins.ci.plugins.jobimport.utils.RemoteItemUtils;
import org.jenkins.ci.plugins.jobimport.utils.URLUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.ForwardToView;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.verb.POST;

import javax.servlet.ServletException;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author <a href="mailto:jieryn@gmail.com">Jesse Farinacci</a>
 * @since 1.0
 */
@Extension
public final class JobImportAction implements RootAction, Describable<JobImportAction> {

  public static final PermissionGroup JOB_IMPORT_PERMISSIONS =
          new PermissionGroup(JobImportAction.class, Messages._Job_Import_Plugin_PermissionGroup());

  public static final Permission JOB_IMPORT =
          new Permission(JOB_IMPORT_PERMISSIONS, "JobImport", Messages._Job_Import_Plugin_Permission(),
                  Jenkins.ADMINISTER, PermissionScope.JENKINS);

  private static final Logger LOG = Logger.getLogger(JobImportAction.class.getName());

  public void doClear(final StaplerRequest request, final StaplerResponse response)
          throws ServletException, IOException {
    response.sendRedirect(Jenkins.get().getRootUrl() + getUrlName());
  }

  @POST
  @Restricted(NoExternalUse.class)
  public void doImport(final StaplerRequest request, final StaplerResponse response)
          throws ServletException, IOException {

    Jenkins.get().checkPermission(JOB_IMPORT);

    final SortedMap<RemoteItem, RemoteItemImportStatus> remoteJobsImportStatus = new TreeMap<>();

    final String localFolder = request.getParameter(Constants.LOCAL_FOLDER_PARAM);
    final String remoteJobsAvailable = (String) request.getParameter("remoteJobsAvailable");

    final String site = (String) request.getParameter("remoteJenkins");

    JenkinsSite remoteJenkins = new JenkinsSite("", "");
    for (JenkinsSite js : JobImportGlobalConfig.get().getSites()) {
      if ((js.getName() + "-" + js.getUrl() + "-" + js.getDefaultCredentialsId()).equals(site)) {
        remoteJenkins = js;
        break;
      }
    }

    final String credentialId = remoteJenkins.getDefaultCredentialsId();

    final SortedSet<RemoteItem> remoteJobs = new TreeSet<>();
    final String remoteFolder = request.getParameter("remoteFolder");
    final String remoteUrl = URLUtils.safeURL(remoteJenkins.getUrl(), remoteFolder);
    final String recursiveSearch = request.getParameter(Constants.RECURSIVE_PARAM);

    doQueryInternal(null, remoteUrl, CredentialsUtils.getCredentials(credentialId), recursiveSearch, remoteJobs);

    if (remoteJobsAvailable != null && remoteJobsAvailable.equalsIgnoreCase("true")) {
      if (request.hasParameter(Constants.JOB_URL_PARAM)) {
        for (final String jobUrl : Arrays.asList(request.getParameterValues(Constants.JOB_URL_PARAM))) {
          doImportInternal(jobUrl, localFolder, credentialId, shouldInstallPlugins(request.getParameter("plugins")), shouldUpdate(request.getParameter("update")), shouldDisable(request.getParameter("disable-" + jobUrl)), remoteJobs, remoteJobsImportStatus);
        }
      }
    }

    new ForwardToView(this, "index")
            .with("step2", "true")
            .with("remoteJobsAvailable", remoteJobsAvailable)
            .with("remoteJobsImportStatus", remoteJobsImportStatus)
            .with("remoteJobsImportStatusAvailable", remoteJobsImportStatus.size() > 0)
            .generateResponse(request, response, this);
  }

  @POST
  public void doQuery(final StaplerRequest request, final StaplerResponse response)
          throws ServletException, IOException {

    Jenkins.get().checkPermission(JOB_IMPORT);

    final SortedSet<RemoteItem> remoteJobs = new TreeSet<>();

    final String remoteFolder = request.getParameter("remoteFolder");

    final String site = request.getParameter("_.jenkinsSites");

    JenkinsSite remoteJenkins = new JenkinsSite("", "");
    for (JenkinsSite js : JobImportGlobalConfig.get().getSites()) {
      if ((js.getName() + "-" + js.getUrl() + "-" + js.getDefaultCredentialsId()).equals(site)) {
        remoteJenkins = js;
        break;
      }
    }

    final String credentialId = remoteJenkins.getDefaultCredentialsId();
    final String remoteUrl = URLUtils.safeURL(remoteJenkins.getUrl(), remoteFolder);
    final String recursiveSearch = request.getParameter(Constants.RECURSIVE_PARAM);

    doQueryInternal(null, remoteUrl, CredentialsUtils.getCredentials(credentialId), recursiveSearch, remoteJobs);

    new ForwardToView(this, "index")
            .with("step1", "true")
            .with("remoteJenkins", remoteJenkins.getName() + "-" + remoteJenkins.getUrl() + "-" + remoteJenkins.getDefaultCredentialsId())
            .with("remoteJobs", remoteJobs)
            .with("remoteFolder", remoteFolder)
            .with("recursiveSearch", recursiveSearch)
            .with("remoteJobsAvailable", remoteJobs.size() > 0)
            .generateResponse(request, response, this);
  }

  private void doImportInternal(String jobUrl, String localPath,
                                String credentialId,
                                boolean installPlugins,
                                boolean update,
                                boolean disable,
                                SortedSet<RemoteItem> remoteJobs,
                                SortedMap<RemoteItem, RemoteItemImportStatus> remoteJobsImportStatus) throws IOException {
    final RemoteItem remoteJob = RemoteItemUtils.getRemoteJob(remoteJobs, jobUrl);
    if (remoteJob != null) {
      if (!remoteJobsImportStatus.containsKey(remoteJob)) {
        remoteJobsImportStatus.put(remoteJob, new RemoteItemImportStatus(remoteJob));
      }

      // ---
      if (!update && StringUtils.isNotEmpty(localPath) && Jenkins.get().getItemByFullName(localPath + remoteJob.getName()) != null) {
        remoteJobsImportStatus.get(remoteJob).setStatus(MessagesUtils.formatFailedDuplicateJobName());
      } else if (!update && StringUtils.isEmpty(localPath) && Jenkins.get().getItem(remoteJob.getName()) != null) {
        remoteJobsImportStatus.get(remoteJob).setStatus(MessagesUtils.formatFailedDuplicateJobName());
      } else {
        InputStream inputStream = null;

        NullSafeCredentials credentials = CredentialsUtils.getCredentials(credentialId);

        try {
          inputStream = URLUtils.fetchUrl(remoteJob.getUrl() + "/config.xml", credentials.username, credentials.password);

          final Item newItem;
          if (StringUtils.isNotEmpty(localPath) && !StringUtils.equals("/", localPath.trim())) {
            Item currentItem = Jenkins.get().getItemByFullName(remoteJob.getFullName());
            if (update && currentItem instanceof AbstractItem) {
              ((AbstractItem) currentItem).updateByXml((Source) new StreamSource(inputStream));
              newItem = currentItem;
            } else {
              if (!folderExists(localPath)) {
                makeFolders(Arrays.asList(localPath.split("/")));
              }
              newItem = getFolder(localPath).createProjectFromXML(remoteJob.getName(), inputStream);
            }
          } else {
            Item currentItem = Jenkins.get().getItemByFullName(remoteJob.getFullName());
            if (update && currentItem instanceof AbstractItem) {
              ((AbstractItem) currentItem).updateByXml((Source) new StreamSource(inputStream));
              newItem = currentItem;
            } else {
              newItem = remoteJob.hasParent()
                  ? addNewItemWithFolders(remoteJob, inputStream)
                  : Jenkins.get().createProjectFromXML(remoteJob.getName(), inputStream);
            }
          }

          PluginManager manager = PluginManager.createDefault(Jenkins.get());

          if (newItem != null) {

            if (installPlugins) {
              Jenkins instance = Jenkins.get();
              instance.getAuthorizationStrategy().getACL(instance).checkPermission(Jenkins.ADMINISTER);
              manager.prevalidateConfig(URLUtils.fetchUrl(remoteJob.getUrl() + "/config.xml", credentials.username, credentials.password));
            }

            newItem.save();
            
            boolean canDisable = newItem instanceof AbstractProject;
            
            if (canDisable && disable) {
              ((AbstractProject) newItem).disable();
            }
          }
          
          Map<String, VersionNumber> requiredPlugins = manager.parseRequestedPlugins(URLUtils.fetchUrl(remoteJob.getUrl() + "/config.xml", credentials.username, credentials.password));

          TreeMap<String, String> installedPlugins = new TreeMap<>();
          for (PluginWrapper plugin : Jenkins.get().pluginManager.getPlugins()) {
            installedPlugins.put(plugin.getShortName(), plugin.getVersion());
          }

          TreeMap<String, String> missingPlugins = new TreeMap<>();
          for (String plugin : requiredPlugins.keySet()) {
            if ( !installedPlugins.containsKey(plugin) ) {
              missingPlugins.put(plugin, requiredPlugins.get(plugin).toString());
            }
          }

          remoteJob.setPlugins(missingPlugins);
          
          remoteJobsImportStatus.get(remoteJob).setStatus(MessagesUtils.formatSuccess());

          if (remoteJob.isFolder() && ((RemoteFolder) remoteJob).hasChildren()) {
            for (RemoteItem childJob : ((RemoteFolder) remoteJob).getChildren()) {
              doImportInternal(childJob.getUrl(), newItem.getFullName(), credentialId, installPlugins, update, disable, remoteJobs, remoteJobsImportStatus);
            }
          }
        } catch (final Exception e) {
          LOG.warning("Job Import Failed: " + e.getMessage());
          if (LOG.isLoggable(Level.INFO)) {
            LOG.log(Level.INFO, e.getMessage(), e);
          }
          remoteJobsImportStatus.get(remoteJob).setStatus(MessagesUtils.formatFailedException(e));

          try {
            TopLevelItem created = Jenkins.get().getItem(remoteJob.getName());
            if (created != null) {
              created.delete();
            }
          } catch (final InterruptedException e2) {
            // do nothing
          }
        } finally {
          IOUtils.closeQuietly(inputStream);
        }
      }
    }
  }

  private boolean folderExists(String folder) throws IOException {
    return null != getFolder(folder);
  }

  private Folder getFolder(String folder) throws IOException {
    return Jenkins.get().getItemByFullName(folder, Folder.class);
  }

  private String makeFolders(List<String> input) throws IOException {
    List<String> folders = new ArrayList<>();
    folders.addAll(input);
    StringBuilder path = new StringBuilder();
    path.append(folders.get(0));
    if (!folderExists(path.toString())) {
      Jenkins.get().createProject(Folder.class, path.toString());
    }
    folders.remove(0);
    for (String folder : folders) {
      String prevPath = path.toString();
      path.append(Constants.SEPARATOR).append(folder);
      if (!folderExists(path.toString())) {
        getFolder(prevPath).createProject(Folder.class, folder);
      }
    }
    return path.toString();
  }

  private Item addNewItemWithFolders(final RemoteItem remoteJob, InputStream inputStream) throws IOException ,IllegalStateException, IOException  {
    List<String> folders = new ArrayList<>();
    folders.addAll(Arrays.asList(remoteJob.getFullName().split(Constants.SEPARATOR)));
    //last is the job name
    folders.remove(folders.size() - 1);
    return getFolder(makeFolders(folders)).createProjectFromXML(remoteJob.getName(), inputStream);
  }

  private void doQueryInternal(RemoteFolder parent, String url, NullSafeCredentials credentials, String recursiveSearch, SortedSet<RemoteItem> remoteJobs) {
    remoteJobs.addAll(RestApiClient.getRemoteItems(parent, url, credentials, isRecursive(recursiveSearch)));
  }

  private boolean isRecursive(String param) {
    return StringUtils.equals("on", param);
  }

  private boolean shouldInstallPlugins(String param) {
    return StringUtils.equals("on", param);
  }

  private boolean shouldUpdate(String param) {
    return StringUtils.equals("on", param);
  }

  private boolean shouldDisable(String param) {
    return StringUtils.equals("on", param);
  }

  public String getRootUrl() {
      return Jenkins.get().getRootUrl();
  }

  @Override
  public String getDisplayName() {
    return Messages.DisplayName();
  }

  @Override
  public String getIconFileName() {
    return "setting.png";
  }

  @Override
  public String getUrlName() {
    return "/" + Constants.URL_NAME;
  }

  @Override
  public Descriptor<JobImportAction> getDescriptor() {
    return Jenkins.get().getDescriptorOrDie(getClass());
  }

  @Extension
  public static final class JobImportActionDescriptor extends Descriptor<JobImportAction> {

    @Override
    public String getDisplayName() { return ""; }

    public ListBoxModel doFillCredentialIdItems() {
      if (!Jenkins.get().hasPermission(JOB_IMPORT)) {
        return new StandardListBoxModel();
      }
      return new StandardListBoxModel()
              .includeEmptyValue()
              .includeMatchingAs(
                      Jenkins.getAuthentication(),
                      Jenkins.getInstanceOrNull(),
                      StandardUsernamePasswordCredentials.class,
                      Collections.<DomainRequirement>emptyList(),
                      CredentialsMatchers.always()
              ).includeMatchingAs(
                      ACL.SYSTEM,
                      Jenkins.getInstanceOrNull(),
                      StandardUsernamePasswordCredentials.class,
                      Collections.<DomainRequirement>emptyList(),
                      CredentialsMatchers.always()
              );
    }

    public ListBoxModel doFillJenkinsSitesItems() {
      if (!Jenkins.get().hasPermission(JOB_IMPORT)) {
        return new StandardListBoxModel();
      }
      final ListBoxModel listBoxModel = new ListBoxModel();
      JobImportGlobalConfig.get().getSites().stream().forEach(s -> listBoxModel.add(s.getName(), s.getName() + "-" + s.getUrl() + "-" + s.getDefaultCredentialsId()));
      return listBoxModel;
    }
  }
}
