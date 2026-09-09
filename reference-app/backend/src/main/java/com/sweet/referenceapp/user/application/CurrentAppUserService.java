package com.sweet.referenceapp.user.application;

import com.sweet.referenceapp.user.domain.AppUserRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CurrentAppUserService {
    private final AppUserRepository repository;

    public CurrentAppUserService(AppUserRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<AppUserView> find(UUID id) {
        return repository.findById(id).map(AppUserView::from);
    }
}
