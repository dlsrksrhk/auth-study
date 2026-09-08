package com.sweet.referenceapp.user.domain;

public interface AppBootstrapStateRepository {
    AppBootstrapState findSingletonForUpdate();

    void complete(AppBootstrapState completed);
}
