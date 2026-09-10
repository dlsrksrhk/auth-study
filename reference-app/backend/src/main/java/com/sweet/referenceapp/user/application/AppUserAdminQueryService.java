package com.sweet.referenceapp.user.application;

import static com.sweet.referenceapp.user.application.AppUserAdminException.Code.APP_USER_NOT_FOUND;

import com.sweet.referenceapp.user.domain.AppUserRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppUserAdminQueryService {
    private final AppUserRepository users;

    public AppUserAdminQueryService(AppUserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AppUserAdminPage list(AppUserAdminQuery query) {
        if (query == null) {
            throw new AppUserAdminException(AppUserAdminException.Code.INVALID_REQUEST);
        }
        var result = users.findPage(query.page(), query.size(), query.status(), query.role());
        long pages = result.totalElements() / query.size()
                + (result.totalElements() % query.size() == 0 ? 0 : 1);
        return new AppUserAdminPage(result.items().stream().map(AppUserView::from).toList(),
                query.page(), query.size(), result.totalElements(), pages);
    }

    @Transactional(readOnly = true)
    public AppUserView detail(UUID id) {
        if (id == null) {
            throw new AppUserAdminException(AppUserAdminException.Code.INVALID_REQUEST);
        }
        return users.findById(id).map(AppUserView::from)
                .orElseThrow(() -> new AppUserAdminException(APP_USER_NOT_FOUND));
    }
}
