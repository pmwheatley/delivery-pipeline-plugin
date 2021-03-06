/*
This file is part of Delivery Pipeline Plugin.

Delivery Pipeline Plugin is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

Delivery Pipeline Plugin is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with Delivery Pipeline Plugin.
If not, see <http://www.gnu.org/licenses/>.
*/
package se.diabol.jenkins.pipeline.domain;

import au.com.centrumsystems.hudson.plugin.buildpipeline.trigger.BuildPipelineTrigger;
import hudson.Launcher;
import hudson.matrix.Axis;
import hudson.matrix.AxisList;
import hudson.matrix.MatrixConfiguration;
import hudson.matrix.MatrixProject;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.util.OneShotEvent;
import jenkins.model.Jenkins;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Bug;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import se.diabol.jenkins.pipeline.DeliveryPipelineView;
import se.diabol.jenkins.pipeline.PipelineProperty;
import se.diabol.jenkins.pipeline.domain.status.SimpleStatus;
import se.diabol.jenkins.pipeline.domain.status.Status;

import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;

import static org.junit.Assert.*;

public class TaskTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    @Test
    public void testGetAg() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("test");
        jenkins.getInstance().setQuietPeriod(0);

        Task task = Task.getPrototypeTask(project);
        assertNotNull(task);
        assertFalse(task.isManual());

        Task aggregatedTask = task.getAggregatedTask(null, Jenkins.getInstance());
        assertNotNull(aggregatedTask);
        assertNotNull(task.getLink());
        assertEquals(task.getLink(), aggregatedTask.getLink());

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);


        aggregatedTask = task.getAggregatedTask(build, Jenkins.getInstance());
        assertNotNull(aggregatedTask);
        assertEquals("job/test/1/", aggregatedTask.getLink());


    }

    @Test
    public void testManualTask() throws Exception {
        FreeStyleProject a = jenkins.createFreeStyleProject("a");
        FreeStyleProject b = jenkins.createFreeStyleProject("b");
        a.getPublishersList().add(new BuildPipelineTrigger("b", null));
        jenkins.getInstance().rebuildDependencyGraph();

        Task task = Task.getPrototypeTask(b);
        assertTrue(task.isManual());

    }

    @Test
    public void testGetLatestRunning() throws Exception {
        final OneShotEvent buildStarted = new OneShotEvent();
        final OneShotEvent buildBuilding = new OneShotEvent();

        FreeStyleProject project = jenkins.createFreeStyleProject("test");
        project.getBuildersList().add(new TestBuilder() {
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
                                   BuildListener listener) throws InterruptedException, IOException {
                buildStarted.signal();
                buildBuilding.block();
                return true;
            }
        });
        Task prototype = Task.getPrototypeTask(project);

        project.scheduleBuild2(0);
        buildStarted.block(); // wait for the build to really start
        Task latest = prototype.getLatestTask(jenkins.getInstance(), project.getLastBuild());
        Task aggregated = prototype.getAggregatedTask(project.getLastBuild(), jenkins.getInstance());
        assertEquals("job/test/1/console", latest.getLink());
        assertTrue(latest.getStatus().isRunning());

        assertEquals("job/test/1/console", aggregated.getLink());
        assertTrue(aggregated.getStatus().isRunning());
        buildBuilding.signal();
        jenkins.waitUntilNoActivity();

    }


    @Test
    @Bug(22654)
    public void testTaskNameForMultiConfiguration() throws Exception {
        MatrixProject project = jenkins.createMatrixProject("Multi");
        project.setAxes(new AxisList(new Axis("axis", "foo", "bar")));
        project.addProperty(new PipelineProperty("task", "stage"));

        Collection<MatrixConfiguration> configurations = project.getActiveConfigurations();

        for (MatrixConfiguration configuration : configurations) {
            Task task = Task.getPrototypeTask(configuration);
            assertEquals("task "  + configuration.getName(), task.getName());

        }
    }


    @Test
    public void testFailedThenQueued() throws Exception {
        FreeStyleProject a = jenkins.createFreeStyleProject("a");
        FreeStyleProject b = jenkins.createFreeStyleProject("b");
        jenkins.setQuietPeriod(0);
        a.getPublishersList().add(new BuildPipelineTrigger("b", null));
        b.getBuildersList().add(new FailureBuilder());
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(a);
        Task task = Task.getPrototypeTask(b);
        assertTrue(task.getLatestTask(jenkins.getInstance(), build).getStatus().isIdle());

        DeliveryPipelineView view = new DeliveryPipelineView("Pipeline", jenkins.getInstance());
        view.triggerManual("b", "a", "1");
        jenkins.waitUntilNoActivity();
        assertTrue(task.getLatestTask(jenkins.getInstance(), build).getStatus().isFailed());
        jenkins.getInstance().setNumExecutors(0);
        jenkins.getInstance().reload();
        view.triggerManual("b", "a", "1");

        assertTrue(task.getLatestTask(jenkins.getInstance(), build).getStatus().isQueued());

    }


}
