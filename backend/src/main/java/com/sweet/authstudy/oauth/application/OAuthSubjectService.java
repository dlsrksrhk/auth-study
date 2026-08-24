package com.sweet.authstudy.oauth.application;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

import com.sweet.authstudy.oauth.domain.OAuthSubject;
import com.sweet.authstudy.oauth.domain.OAuthSubjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OAuthSubjectService {

    private final OAuthSubjectRepository repository;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public OAuthSubjectService(
            OAuthSubjectRepository repository,
            Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public OAuthSubject getOrCreate(long accountId) {
        var existing = repository.findByAccountId(accountId);
        return existing.orElseGet(() -> createOrFindWinner(accountId));
    }

    private OAuthSubject createOrFindWinner(long accountId) {
        OAuthSubject candidate = OAuthSubject.create(accountId, randomUuid(), clock.instant());
        repository.insertIfAbsent(candidate);
        return repository.findByAccountId(accountId)
                .orElseThrow(() -> new IllegalStateException("OAuth subject insert did not produce a winner."));
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
