/*
 * Copyright 2011 DTO Solutions, Inc. (http://dtosolutions.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

/*
* JschScpFileCopier.java
* 
* User: Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
* Created: 3/21/11 4:47 PM
* 
*/
package com.dtolabs.rundeck.core.execution.impl.jsch;

import com.dtolabs.rundeck.core.Constants;
import com.dtolabs.rundeck.core.common.Framework;
import com.dtolabs.rundeck.core.common.INodeEntry;
import com.dtolabs.rundeck.core.execution.ExecutionContext;
import com.dtolabs.rundeck.core.execution.impl.common.BaseFileCopier;
import com.dtolabs.rundeck.core.execution.service.FileCopier;
import com.dtolabs.rundeck.core.execution.service.FileCopierException;
import com.dtolabs.rundeck.core.plugins.configuration.Describable;
import com.dtolabs.rundeck.core.plugins.configuration.Description;
import com.dtolabs.rundeck.core.plugins.configuration.Property;
import com.dtolabs.rundeck.core.tasks.net.SSHTaskBuilder;
import com.jcraft.jsch.JSchException;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.Task;
import org.apache.tools.ant.taskdefs.Echo;
import org.apache.tools.ant.taskdefs.Sequential;

import java.io.*;
import java.text.MessageFormat;
import java.util.List;
import java.util.Map;

/**
 * JschScpFileCopier is ...
 *
 * @author Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
 */
public class JschScpFileCopier extends BaseFileCopier implements FileCopier, Describable {
    public static final String SERVICE_PROVIDER_TYPE = "jsch-scp";


    static final Description DESC = new Description() {
        public String getName() {
            return SERVICE_PROVIDER_TYPE;
        }

        public String getTitle() {
            return "SCP";
        }

        public String getDescription() {
            return "Copies a script file to a remote node via SCP.";
        }

        public List<Property> getProperties() {
            return JschNodeExecutor.CONFIG_PROPERTIES;
        }

        public Map<String, String> getPropertiesMapping() {
            return JschNodeExecutor.CONFIG_MAPPING;
        }
    };

    public Description getDescription() {
        return DESC;
    }

    private Framework framework;

    public JschScpFileCopier(Framework framework) {
        this.framework = framework;
    }

    public String copyFileStream(final ExecutionContext context, InputStream input, INodeEntry node) throws
        FileCopierException {

        return copyFile(context, null, input, null, node);
    }

    public String copyFile(final ExecutionContext context, File scriptfile, INodeEntry node) throws
        FileCopierException {
        return copyFile(context, scriptfile, null, null, node);
    }

    public String copyScriptContent(ExecutionContext context, String script, INodeEntry node) throws
        FileCopierException {

        return copyFile(context, null, null, script, node);
    }


    private String copyFile(final ExecutionContext context, final File scriptfile, final InputStream input,
                            final String script, final INodeEntry node) throws FileCopierException {

        Project project = new Project();
        final Sequential seq = new Sequential();
        seq.setProject(project);

        final String remotefile = generateRemoteFilepathForNode(node, (null != scriptfile ? scriptfile.getName()
                                                                                          : "dispatch-script"));
        //write the temp file and replace tokens in the script with values from the dataContext
        final File localTempfile = writeScriptTempFile(context, scriptfile, input, script, node);


//        logger.debug("temp file for node " + node.getNodename() + ": " + temp.getAbsolutePath() + ", datacontext: " + dataContext);
        final Task scp;
        final JschNodeExecutor.NodeSSHConnectionInfo nodeAuthentication = new JschNodeExecutor.NodeSSHConnectionInfo(node,
            framework, context);
        try {

            scp = SSHTaskBuilder.buildScp(node, project, remotefile, localTempfile, nodeAuthentication,
                context.getLoglevel());
        } catch (SSHTaskBuilder.BuilderException e) {
            throw new FileCopierException(e);
        }

        /**
         * Copy the file over
         */
        seq.addTask(createEchoVerbose("copying scriptfile: '" + localTempfile.getAbsolutePath()
                                      + "' to: '" + node.getNodename() + ":" + remotefile + "'", project));
        seq.addTask(scp);

        String errormsg = null;
        try {
            seq.execute();
        } catch (BuildException e) {
            if (null != e.getCause() && e.getCause() instanceof JSchException && e.getCause().getMessage().contains(
                "Auth cancel")) {
                String msgformat = JschNodeExecutor.FWK_PROP_AUTH_CANCEL_MSG_DEFAULT;
                if (framework.getPropertyLookup().hasProperty(JschNodeExecutor.FWK_PROP_AUTH_CANCEL_MSG)) {
                    msgformat = framework.getProperty(JschNodeExecutor.FWK_PROP_AUTH_CANCEL_MSG);
                }
                errormsg = MessageFormat.format(msgformat, node.getNodename(),
                    e.getMessage());
            } else {
                errormsg = e.getMessage();
            }
            context.getExecutionListener().log(0, errormsg);
            throw new FileCopierException("[jsch-scp] Failed copying the file: " + errormsg, e);
        }
        if (!localTempfile.delete()) {
            context.getExecutionListener().log(Constants.WARN_LEVEL,
                "Unable to remove local temp file: " + localTempfile.getAbsolutePath());
        }
        return remotefile;
    }

    private Echo createEcho(final String message, final Project project, final String logLevel) {
        final Echo echo = new Echo();
        echo.setProject(project);
        final Echo.EchoLevel level = new Echo.EchoLevel();
        level.setValue(logLevel);
        echo.setLevel(level);
        echo.setMessage(message);
        return echo;
    }

    private Echo createEchoVerbose(final String message, final Project project) {
        return createEcho(message, project, "debug");
    }
}
