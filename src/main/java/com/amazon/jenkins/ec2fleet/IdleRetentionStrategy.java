package com.amazon.jenkins.ec2fleet;

import hudson.model.Computer;
import hudson.model.Node;
import hudson.slaves.Messages;
import hudson.slaves.OfflineCause.SimpleOfflineCause;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.SlaveComputer;
import java.lang.InterruptedException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * User: cyberax
 * Date: 1/12/16
 * Time: 02:56
 */
public class IdleRetentionStrategy extends RetentionStrategy<SlaveComputer>
{
    private final int maxIdleMinutes;
    private final EC2FleetCloud parent;

    private static final Logger LOGGER = Logger.getLogger(IdleRetentionStrategy.class.getName());

    public IdleRetentionStrategy(final int maxIdleMinutes, final EC2FleetCloud parent) {
        this.maxIdleMinutes = maxIdleMinutes;
        this.parent = parent;
        LOGGER.log(Level.INFO, "Idle Retention initiated");
    }

    protected boolean isIdleForTooLong(final Computer c) {
        long age = System.currentTimeMillis()-c.getIdleStartMilliseconds();
        long maxAge = maxIdleMinutes*60*1000;
        LOGGER.log(Level.FINE, "Instance: " + c.getDisplayName() + " Age: " + age + " Max Age:" + maxAge);
        return age > maxAge;
    }

    @Override public long check(final SlaveComputer c) {
        // Ensure that the EC2FleetCloud cannot be mutated from under us while
        // we're doing this check
        synchronized(parent) {
            // Ensure nobody provisions onto this node until we've done
            // checking
            boolean shouldAcceptTasks = c.isAcceptingTasks();
            c.setAcceptingTasks(false);
            try {
                if (isIdleForTooLong(c)) {
                    // node no longer eligible for tasks after idle timeout
                    shouldAcceptTasks = false;
                    // Find instance ID
                    Node compNode = c.getNode();
                    if (compNode == null) {
                        return 0;
                    }
                    final String nodeId = compNode.getNodeName();
                    // disconnect and then terminate
                    if (c.isOnline()) {
                        c.disconnect(SimpleOfflineCause.create(
                            Messages._RetentionStrategy_Demand_OfflineIdle()));
                        c.waitUntilOffline();
                    }
                    parent.terminateInstance(nodeId);
                }
            } catch (InterruptedException e) {
                LOGGER.log(Level.WARNING, "Interrupted while diconnecting " + c.getDisplayName());
            } finally {
                c.setAcceptingTasks(shouldAcceptTasks);
            }
        }

        return 1;
    }

    @Override public void start(SlaveComputer c) {
        LOGGER.log(Level.INFO, "Connecting to instance: " + c.getDisplayName());
        c.connect(false);
    }
}
