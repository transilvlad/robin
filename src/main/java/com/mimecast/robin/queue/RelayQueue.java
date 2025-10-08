package com.mimecast.robin.queue;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

import java.util.List;

/**
 * Relay queue using ObjectDB.
 */
public class RelayQueue {
    private static final String DB_URL = "objectdb:sessions.odb";
    private final EntityManagerFactory emf = Persistence.createEntityManagerFactory(DB_URL);

    /**
     * Enqueue a RelaySession.
     *
     * @param relaySession RelaySession instance.
     */
    public void enqueue(RelaySession relaySession) {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        em.persist(relaySession);
        em.getTransaction().commit();
        em.close();
    }

    /**
     * Dequeue a RelaySession.
     *
     * @return RelaySession instance or null if none.
     */
    public RelaySession dequeue() {
        EntityManager em = emf.createEntityManager();
        List<RelaySession> sessions = em.createQuery("SELECT rs FROM RelaySession rs", RelaySession.class)
                .setMaxResults(1)
                .getResultList();
        RelaySession relaySession = sessions.isEmpty() ? null : sessions.get(0);

        if (relaySession != null) {
            em.getTransaction().begin();
            em.remove(relaySession);
            em.getTransaction().commit();
        }
        em.close();
        return relaySession;
    }

    public void close() {
        emf.close();
    }
}
