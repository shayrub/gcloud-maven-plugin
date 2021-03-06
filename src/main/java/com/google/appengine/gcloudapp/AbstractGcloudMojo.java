/**
 * Copyright 2014 Google Inc. All Rights Reserved.
 */
package com.google.appengine.gcloudapp;


import com.google.appengine.SdkResolver;
import com.google.appengine.tools.admin.AppCfg;
import com.google.apphosting.utils.config.AppEngineApplicationXml;
import com.google.apphosting.utils.config.AppEngineApplicationXmlReader;
import com.google.apphosting.utils.config.AppEngineWebXml;
import com.google.apphosting.utils.config.AppEngineWebXmlReader;
import com.google.apphosting.utils.config.EarHelper;
import static com.google.common.base.Charsets.UTF_8;
import com.google.common.base.Joiner;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.ini4j.Ini;

/**
 *
 * @author ludo
 */
public abstract class AbstractGcloudMojo extends AbstractMojo {

  /**
   * @parameter property="project"
   * @required
   * @readonly
   */
  protected MavenProject maven_project;

  /**
   * gcloud installation gcloud_directory
   *
   * @parameter expression="${gcloud.gcloud_directory}"
   */
  protected String gcloud_directory;

  /**
   * docker_host
   *
   * @parameter expression="${gcloud.docker_host}" default-value="ENV_or_default"
   */
  protected String docker_host;
  /**
   * docker_tls_verify
   *
   * @parameter expression="${gcloud.docker_tls_verify}" default-value="ENV_or_default"
   */
  protected String docker_tls_verify;

  /**
   * docker_host_cert_path
   *
   * @parameter expression="${gcloud.docker_cert_path}" default-value="ENV_or_default"
   */
  protected String docker_cert_path;

  /**
   * Override the default verbosity for this command. This must be a standard
   * logging verbosity level: [debug, info, warning, error, critical, none]
   * (Default: [info]).
   *
   * @parameter expression="${gcloud.verbosity}"
   */
  protected String verbosity;

  /**
   * Google Cloud Platform gcloud_project to use for this invocation.
   *
   * @parameter expression="${gcloud.gcloud_project}"
   */
  protected String gcloud_project;

 /**
   * version The version of the app that will be created or replaced by this
   * deployment.
   *
   * @parameter expression="${gcloud.version}"
   */
  protected String version;
  
  /**
   * Quiet mode, if true does not ask to perform the action.
   *
   * @parameter expression="${gcloud.quiet}" default-value="true"
   */
  protected boolean quiet = true;
  
  protected AppEngineWebXml appengineWebXml = null;

  /**
   * The location of the appengine application to run.
   *
   * @parameter expression="${gcloud.application_directory}"
   */
  protected String application_directory;
  
  /**
   * Non Docker mode (Experimental, will disappear soon).
   *
   * @parameter expression="${gcloud.non_docker_mode}" default-value="false"
   */
  protected boolean non_docker_mode = false;
  

  protected abstract ArrayList<String> getCommand(String appDir) throws MojoExecutionException;

  protected ArrayList<String> setupInitialCommands(ArrayList<String> commands, boolean deploy) throws MojoExecutionException {
  String pythonLocation = "python"; //default in the path for Linux
    boolean isWindows = System.getProperty("os.name").contains("Windows");
    if (isWindows) {
      pythonLocation = System.getenv("CLOUDSDK_PYTHON");
      if (pythonLocation == null) {
        getLog().info("CLOUDSDK_PYTHON env variable is not defined. Choosing a default python.exe interpreter.");
        getLog().info("If this does not work, please set CLOUDSDK_PYTHON to a correct Python interpreter location.");

        pythonLocation = "python.exe";
      }
    } else {
      String possibleLinuxPythonLocation = System.getenv("CLOUDSDK_PYTHON");
      if (possibleLinuxPythonLocation != null) {
        getLog().info("Found a python interpreter specified via CLOUDSDK_PYTHON at: " + possibleLinuxPythonLocation);
        pythonLocation = possibleLinuxPythonLocation;
      }
    }

    commands.add(pythonLocation);
    commands.add("-S");

    boolean error = false;
    if (gcloud_directory == null) {
      if (isWindows) {
        String programFiles = System.getenv("ProgramFiles");
        if (programFiles == null) {
          programFiles = System.getenv("ProgramFiles(x86)");
        }
        if (programFiles == null) {
          error = true;
        } else {
          gcloud_directory = programFiles + "\\Google\\Cloud SDK\\google-cloud-sdk";
        }
      } else {
        gcloud_directory = System.getProperty("user.home") + "/google-cloud-sdk";
        if (!new File(gcloud_directory).exists()) {
          // try devshell VM:
          gcloud_directory = "/google/google-cloud-sdk";
          if (!new File(gcloud_directory).exists()) {
            // try bitnani Jenkins VM:
            gcloud_directory = "/usr/local/share/google/google-cloud-sdk";
          }
        }
      }
    }
    File s = new File(gcloud_directory);
    File script = new File(s, "/lib/googlecloudsdk/gcloud/gcloud.py");

    if (error || !script.exists()) {
      getLog().error("Cannot determine the location of the Google Cloud SDK:");
      getLog().error("The script '" + script.getAbsolutePath() + "' does not exist.");
      getLog().error("You can set it via <gcloud_directory> </gcloud_directory> in the pom.xml");
      getLog().info("If you need to install the Google Cloud SDK, follow the instructions located at https://cloud.google.com/appengine/docs/java/managed-vms");
      throw new MojoExecutionException("Unkown Google Cloud SDK location.");
    }

    script = new File(s, "/bin/dev_appserver.py");

    if (!script.exists()) {
      getLog().error("Cannot find the script: " + script.getAbsolutePath());
      getLog().info("You might want to run the command: gcloud components update gae-python");
      throw new MojoExecutionException("Install the correct Cloud SDK components");
    }
    
    if (deploy) {
      commands.add(gcloud_directory + "/lib/googlecloudsdk/gcloud/gcloud.py");
      if (quiet) {
        commands.add("--quiet");
      }
    } else {
      commands.add(gcloud_directory + "/bin/dev_appserver.py");

    }
    String projectId = getAppId();

    if (projectId != null) {
      if (deploy) {
        commands.add("--project=" + projectId);
      } else {
        commands.add("-A");
        commands.add(projectId);
      }
    }
    if (verbosity != null) {
      commands.add("--verbosity=" + verbosity);
    }
    
    if (deploy) {
      commands.add("preview");
      commands.add("app");
    }
    return commands;
  }

  protected ArrayList<String> setupExtraCommands(ArrayList<String> commands) throws MojoExecutionException {
    return commands;
  }

  protected static enum WaitDirective {

    WAIT_SERVER_STARTED,
    WAIT_SERVER_STOPPED
  }

  protected void startCommand(File appDirFile, ArrayList<String> devAppServerCommand, WaitDirective waitDirective) throws MojoExecutionException {
    getLog().info("Running " + Joiner.on(" ").join(devAppServerCommand));
     
    if (!new File(appDirFile, "Dockerfile").exists()) {
      PrintWriter out;
      try {
        out = new PrintWriter(new File(appDirFile, "Dockerfile"));
        out.println("FROM gcr.io/google_appengine/java-compat");
        out.println("ADD . /app");
        out.close();
      } catch (FileNotFoundException ex) {
          throw new MojoExecutionException("Error: creating default Dockerfile " + ex);
      }
    }
    
    Thread stdOutThread;
    Thread stdErrThread;
    try {

      ProcessBuilder processBuilder = new ProcessBuilder(devAppServerCommand);

      processBuilder.directory(appDirFile);

      processBuilder.redirectErrorStream(true);
      Map<String, String> env = processBuilder.environment();
      String env_docker_host = env.get("DOCKER_HOST");
      String docker_host_tls_verify = env.get("DOCKER_TLS_VERIFY");
      String docker_host_cert_path = env.get("DOCKER_CERT_PATH");
      boolean userDefined = (env_docker_host != null)
              || (docker_host_tls_verify != null)
              || (docker_host_cert_path != null);

      if (!userDefined) {
        if ("ENV_or_default".equals(docker_host)) {
          if (env_docker_host == null) {
            if (env.get("DEVSHELL_CLIENT_PORT") != null) {
              // we know we have a good chance to be in an old Google devshell:
              env_docker_host = "unix:///var/run/docker.sock";
            } else {
              // we assume boot2doker environment (Windows, Mac, and some Linux)
              env_docker_host = "tcp://192.168.59.103:2376";
            }
          }
        } else {
          env_docker_host = docker_host;
        }
        env.put("DOCKER_HOST", env_docker_host);

        // we handle TLS extra variables only when we are tcp:
        if (env_docker_host.startsWith("tcp")) {
          if ("ENV_or_default".equals(docker_tls_verify)) {
            if (env.get("DOCKER_TLS_VERIFY") == null) {
              env.put("DOCKER_TLS_VERIFY", "1");
            }
          } else {
            env.put("DOCKER_TLS_VERIFY", docker_tls_verify);
          }

          if ("ENV_or_default".equals(docker_cert_path)) {
            if (env.get("DOCKER_CERT_PATH") == null) {
              env.put("DOCKER_CERT_PATH",
                      System.getProperty("user.home")
                      + File.separator
                      + ".boot2docker"
                      + File.separator
                      + "certs"
                      + File.separator
                      + "boot2docker-vm"
              );
            }
          } else {
            env.put("DOCKER_CERT_PATH", docker_cert_path);
          }
        }
      }
      //export DOCKER_CERT_PATH=/Users/ludo/.boot2docker/certs/boot2docker-vm
      //export DOCKER_TLS_VERIFY=1
      //export DOCKER_HOST=tcp://192.168.59.103:2376
      if (non_docker_mode) {
        env.put ("GAE_LOCAL_VM_RUNTIME", "true");
      }
      
      final Process devServerProcess = processBuilder.start();

      final CountDownLatch waitStartedLatch = new CountDownLatch(1);

      final Scanner stdOut = new Scanner(devServerProcess.getInputStream());
      stdOutThread = new Thread("standard-out-redirection-devappserver") {
        @Override
        public void run() {
          try {
            long healthCount = 0;
            while (stdOut.hasNextLine() && !Thread.interrupted()) {
              String line = stdOut.nextLine();
              // emit this every 30 times, no need for more...
              if (line.contains("GET /_ah/health?IsLastSuccessful=yes HTTP/1.1\" 200 2")) {
                waitStartedLatch.countDown();
                if (healthCount % 20 == 0) {
                  getLog().info(line);
                }
                healthCount++;
              } else {
                getLog().info(line);
              }
            }
          } finally {
            waitStartedLatch.countDown();
          }
        }
      };
      stdOutThread.setDaemon(true);
      stdOutThread.start();

      final Scanner stdErr = new Scanner(devServerProcess.getErrorStream());
      stdErrThread = new Thread("standard-err-redirection-devappserver") {
        @Override
        public void run() {
          while (stdErr.hasNextLine() && !Thread.interrupted()) {
            getLog().error(stdErr.nextLine());
          }
        }
      };
      stdErrThread.setDaemon(true);
      stdErrThread.start();
      if (waitDirective == WaitDirective.WAIT_SERVER_STOPPED) {
        Runtime.getRuntime().addShutdownHook(new Thread("destroy-devappserver") {
          @Override
          public void run() {
            if (devServerProcess != null) {
              devServerProcess.destroy();
            }
          }
        });

        devServerProcess.waitFor();
        int status = devServerProcess.exitValue();
        if (status != 0) {
          getLog().error("Error: gcloud app xxx exit code is: " + status);
          throw new MojoExecutionException("Error: gcloud app xxx exit code is: " + status);
        }
      } else if (waitDirective == WaitDirective.WAIT_SERVER_STARTED) {
        waitStartedLatch.await();
        getLog().info("");
        getLog().info("App Engine Dev Server started in Async mode and running.");
        getLog().info("you can stop it with this command: mvn gcloud:run_stop");
      }
    } catch (IOException e) {
      throw new MojoExecutionException("Could not start the dev app server", e);
    } catch (InterruptedException e) {
    }
  }

  protected String getApplicationDirectory() throws MojoExecutionException {
    if (application_directory != null) {
      return application_directory;
    }
    application_directory = maven_project.getBuild().getDirectory() + "/" + maven_project.getBuild().getFinalName();
    File appDirFile = new File(application_directory);
    if (!appDirFile.exists()) {
      throw new MojoExecutionException("The application directory does not exist : " + application_directory);
    }
    if (!appDirFile.isDirectory()) {
      throw new MojoExecutionException("The application directory is not a directory : " + application_directory);
    }
    return application_directory;
  }

  protected String getProjectIdfromMetaData() {
    try {
      URL url = new URL("http://metadata/computeMetadata/v1/project/project-id");
      URLConnection connection = url.openConnection();
      connection.setRequestProperty("X-Google-Metadata-Request", "True");
      try (BufferedReader reader
              = new BufferedReader(new InputStreamReader(
                      connection.getInputStream(), UTF_8))) {
        return reader.readLine();
      }
    } catch (IOException ignore) {
      // return null if can't determine
      return null;
    }
  }

  protected String getAppId() throws MojoExecutionException {

    if (gcloud_project != null) {
      return gcloud_project;
    }

    try { // Check for Cloud SDK properties:
      File cloudSDKProperties = new File(System.getProperty("user.home")
              + "/.config/gcloud/properties");
      if (!cloudSDKProperties.exists()) {
        String env = System.getenv("CLOUDSDK_CONFIG");
        if (env != null) {
          cloudSDKProperties = new File(env, "properties");
        }
      }
      if (cloudSDKProperties.exists()) {
        org.ini4j.Ini ini = new org.ini4j.Ini();
        ini.load(new FileReader(cloudSDKProperties));
        Ini.Section section = ini.get("core");
        String project = section.get("project");
        if (project != null) {
          getLog().info("Getting project name: " + project
                  + " from gcloud settings.");
          return project;
        }
      }
      // now try the metadata server location:
      String project = getProjectIdfromMetaData();
      if (project != null) {
        getLog().info("Getting project name: " + project
                + " from the metadata server.");
        return project;
      }
    } catch (IOException ioe) {
      // nothing for now. Trying to read appengine-web.xml.
    }
    
    String appDir = getApplicationDirectory();
    if (EarHelper.isEar(appDir)) { // EAR project
      AppEngineApplicationXmlReader reader
              = new AppEngineApplicationXmlReader();
      AppEngineApplicationXml appEngineApplicationXml = reader.processXml(
              getInputStream(new File(appDir, "META-INF/appengine-application.xml")));
      return appEngineApplicationXml.getApplicationId();

    }
    if (new File(appDir, "WEB-INF/appengine-web.xml").exists()) {
      return getAppEngineWebXml().getAppId();
    } else {
      return null;
    }
  }

  private static InputStream getInputStream(File file) {
    try {
      return new FileInputStream(file);
    } catch (FileNotFoundException fnfe) {
      throw new IllegalStateException("File should exist - '" + file + "'");
    }
  }

  protected AppEngineWebXml getAppEngineWebXml() throws MojoExecutionException {
    if (appengineWebXml == null) {
      AppEngineWebXmlReader reader = new AppEngineWebXmlReader(getApplicationDirectory());
      appengineWebXml = reader.readAppEngineWebXml();
    }
    return appengineWebXml;
  }
  
  
  /**
   * The entry point to Aether, i.e. the component doing all the work.
   *
   * @component
   */

  protected RepositorySystem repoSystem;

  /**
   * The current repository/network configuration of Maven.
   *
   * @parameter default-value="${repositorySystemSession}"
   * @readonly
   */
  protected RepositorySystemSession repoSession;

  /**
   * The project's remote repositories to use for the resolution of project
   * dependencies.
   *
   * @parameter default-value="${project.remoteProjectRepositories}"
   * @readonly
   */
  protected List<RemoteRepository> projectRepos;

  /**
   * The project's remote repositories to use for the resolution of plugins and
   * their dependencies.
   *
   * @parameter default-value="${project.remotePluginRepositories}"
   * @readonly
   */
  protected List<RemoteRepository> pluginRepos;

  protected void resolveAndSetSdkRoot() throws MojoExecutionException {

    File sdkBaseDir = SdkResolver.getSdk(maven_project, repoSystem, repoSession, pluginRepos, projectRepos);

    try {
      System.setProperty("appengine.sdk.root", sdkBaseDir.getCanonicalPath());
    } catch (IOException e) {
      throw new MojoExecutionException("Could not open SDK zip archive.", e);
    }
  }
  
  protected void executeAppCfgStagingCommand(String appDir, String destDir,
          ArrayList<String> arguments)
          throws Exception {

    resolveAndSetSdkRoot();
    if (getAppEngineWebXml().getBetaSettings().containsKey("java_quickstart")) {
      arguments.add("--enable_quickstart");
    }
    String appId = getAppId();
    if (appId != null) {
      arguments.add("-A");
      arguments.add(appId);
    }
    if (version != null && !version.isEmpty()) {
      arguments.add("-V");
      arguments.add(version);
    }
    arguments.add("stage");
    arguments.add(appDir);
    arguments.add(destDir);
    getLog().info("Running " + Joiner.on(" ").join(arguments));
    AppCfg.main(arguments.toArray(new String[arguments.size()]));
  }
}
