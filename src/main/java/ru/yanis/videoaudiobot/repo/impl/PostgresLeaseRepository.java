package ru.yanis.videoaudiobot.repo.impl;

import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import ru.yanis.videoaudiobot.repo.LeaseRepository;

@Repository
@Transactional
class PostgresLeaseRepository implements LeaseRepository {
  private final EntityManager em;

  PostgresLeaseRepository(EntityManager em) {
    this.em = em;
  }

  public boolean acquire(String name, UUID token, Duration duration) {
    return em.createNativeQuery(
                """
INSERT INTO worker_lease(name,token,lease_until) VALUES (:name,:token,clock_timestamp()+:seconds*interval '1 second')
ON CONFLICT(name) DO UPDATE SET token=excluded.token, lease_until=excluded.lease_until
WHERE worker_lease.lease_until < clock_timestamp()
""")
            .setParameter("name", name)
            .setParameter("token", token)
            .setParameter("seconds", duration.toSeconds())
            .executeUpdate()
        == 1;
  }

  public boolean renew(String name, UUID token, Duration duration) {
    return em.createNativeQuery(
                """
                UPDATE worker_lease SET lease_until=clock_timestamp()+:seconds*interval '1 second'
                WHERE name=:name AND token=:token AND lease_until>clock_timestamp()
                """)
            .setParameter("name", name)
            .setParameter("token", token)
            .setParameter("seconds", duration.toSeconds())
            .executeUpdate()
        == 1;
  }

  public void release(String name, UUID token) {
    em.createNativeQuery("DELETE FROM worker_lease WHERE name=:name AND token=:token")
        .setParameter("name", name)
        .setParameter("token", token)
        .executeUpdate();
  }
}
