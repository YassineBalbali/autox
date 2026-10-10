package com.autox.scheduling;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Runs a scheduled job on one instance at a time.
 *
 * <p>{@code @Scheduled} is per process: every instance of the API runs every sweeper.
 * With one instance that is invisible, which is what makes it dangerous — "scale out the
 * API" is a change that looks harmless. The image sweeper deletes objects from storage as
 * well as rows from the database, so two instances racing on the same abandoned upload
 * duplicate the work at best.
 *
 * <p>The mechanism is a PostgreSQL <b>session-level advisory lock</b>, which is the right
 * tool here for three reasons: the database is already the one piece of shared state every
 * instance agrees on, so nothing new has to be deployed; the lock is held by a connection,
 * so an instance that crashes mid-sweep releases it the moment its socket closes, with no
 * lease to expire and no stale-lock recovery to get wrong; and {@code pg_try_advisory_lock}
 * does not wait, so a second instance skips the run instead of queueing up to repeat work
 * that has just been done.
 *
 * <p>Advisory locks are keyed on two integers, not a name, so the task name is hashed. A
 * collision between two task names would make them take turns rather than run together —
 * harmless for jobs that are idempotent and minutes apart, which is all of them here.
 *
 * <p>This is deliberately not ShedLock. ShedLock earns its keep when jobs need a recorded
 * run history, or must not re-run for a minimum interval; these are three periodic
 * idempotent sweeps, and a dependency plus a table would buy nothing a lock does not.
 */
@Component
public class ScheduledTaskCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskCoordinator.class);

    /**
     * First half of the advisory key, constant for this application. Advisory locks share
     * one namespace across everything connected to the database, so a fixed prefix keeps
     * AutoX's locks from colliding with anything else that ever uses the same server.
     */
    private static final int LOCK_NAMESPACE = 0x6175_746F;

    private final JdbcTemplate jdbc;

    public ScheduledTaskCoordinator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Runs {@code work} if no other instance is running the same task.
     *
     * @return {@code true} if the work ran, {@code false} if the lock was already held
     */
    public boolean runExclusively(String taskName, Runnable work) {
        int taskKey = taskName.hashCode();

        Boolean ran = jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            if (!tryLock(connection, taskKey)) {
                log.debug("Skipping {}: another instance holds the lock", taskName);
                return false;
            }
            try {
                // The lock lives on this connection and nothing else does: the work below
                // takes its own connections and transactions from the pool as usual.
                work.run();
            } finally {
                unlock(connection, taskKey);
            }
            return true;
        });

        return Boolean.TRUE.equals(ran);
    }

    private static boolean tryLock(Connection connection, int taskKey) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT pg_try_advisory_lock(?, ?)")) {
            statement.setInt(1, LOCK_NAMESPACE);
            statement.setInt(2, taskKey);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static void unlock(Connection connection, int taskKey) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT pg_advisory_unlock(?, ?)")) {
            statement.setInt(1, LOCK_NAMESPACE);
            statement.setInt(2, taskKey);
            statement.execute();
        }
    }
}
