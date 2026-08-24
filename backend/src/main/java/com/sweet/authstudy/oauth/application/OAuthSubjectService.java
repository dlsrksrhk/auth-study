package com.sweet.authstudy.oauth.application;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.sweet.authstudy.oauth.domain.OAuthSubject;
import com.sweet.authstudy.oauth.domain.OAuthSubjectRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OAuthSubjectService {

    private final OAuthSubjectRepository repository;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();
    private final TransactionTemplate independentTransaction;

    public OAuthSubjectService(
            OAuthSubjectRepository repository,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.clock = clock;
        this.independentTransaction = new TransactionTemplate(transactionManager);
        this.independentTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public OAuthSubject getOrCreate(long accountId) {
        Optional<OAuthSubject> existing = Objects.requireNonNull(
                independentTransaction.execute(status -> repository.findByAccountId(accountId)));
        return existing.orElseGet(() -> createOrFindWinner(accountId));
    }

    private OAuthSubject createOrFindWinner(long accountId) {
        OAuthSubject candidate = OAuthSubject.create(accountId, randomUuid(), clock.instant());
        try {
            return Objects.requireNonNull(
                    independentTransaction.execute(status -> repository.save(candidate)));
        } catch (DataIntegrityViolationException uniqueRace) {
            Optional<OAuthSubject> winner = Objects.requireNonNull(
                    independentTransaction.execute(status -> repository.findByAccountId(accountId)));
            return winner.orElseThrow(() -> uniqueRace);
        }
    }

    private UUID randomUuid() {
        byte[] bytes = new byte[16];
        secureRandom.nextBytes(bytes);
        bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x40);
        bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
