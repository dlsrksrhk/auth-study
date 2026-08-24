package com.sweet.authstudy.oauth.application;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

import com.sweet.authstudy.oauth.domain.OAuthSubject;
import com.sweet.authstudy.oauth.domain.OAuthSubjectRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OAuthSubjectService {

    private final OAuthSubjectRepository repository;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();
    private final TransactionTemplate newTransaction;

    public OAuthSubjectService(
            OAuthSubjectRepository repository,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.clock = clock;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public OAuthSubject getOrCreate(long accountId) {
        return repository.findByAccountId(accountId).orElseGet(() -> createOrFindWinner(accountId));
    }

    private OAuthSubject createOrFindWinner(long accountId) {
        OAuthSubject candidate = OAuthSubject.create(accountId, randomUuid(), clock.instant());
        try {
            return Objects.requireNonNull(newTransaction.execute(status -> repository.save(candidate)));
        } catch (DataIntegrityViolationException uniqueRace) {
            return repository.findByAccountId(accountId).orElseThrow(() -> uniqueRace);
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
