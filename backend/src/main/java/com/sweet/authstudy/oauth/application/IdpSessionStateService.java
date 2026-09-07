package com.sweet.authstudy.oauth.application;

import com.sweet.authstudy.hr.company.domain.CompanyRepository;
import com.sweet.authstudy.hr.company.domain.CompanyStatus;
import com.sweet.authstudy.hr.user.domain.UserRepository;
import com.sweet.authstudy.hr.user.domain.UserStatus;
import com.sweet.authstudy.identity.domain.AccountRepository;
import com.sweet.authstudy.identity.domain.AccountStatus;
import com.sweet.authstudy.oauth.domain.OAuthSubjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Service
public class IdpSessionStateService {
    public enum State {CURRENT, PASSWORD_CHANGE_REQUIRED, INVALID}

    private final AccountRepository accounts;
    private final UserRepository users;
    private final CompanyRepository companies;
    private final OAuthSubjectRepository subjects;
    private final Clock clock;

    public IdpSessionStateService(AccountRepository accounts, UserRepository users,
                                  CompanyRepository companies, OAuthSubjectRepository subjects, Clock clock) {
        this.accounts = accounts;
        this.users = users;
        this.companies = companies;
        this.subjects = subjects;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public State evaluate(long accountId, Long companyId, Long userId, UUID subject) {
        if (companyId == null || userId == null || subject == null) return State.INVALID;
        var account = accounts.findById(accountId).orElse(null);
        var company = companies.findById(companyId).orElse(null);
        var user = users.findById(userId).orElse(null);
        var oauthSubject = subjects.findByAccountId(accountId).orElse(null);
        if (account == null || company == null || user == null || oauthSubject == null
                || account.status() != AccountStatus.ACTIVE
                || account.lockedUntil() != null && account.lockedUntil().isAfter(clock.instant())
                || company.status() != CompanyStatus.ACTIVE
                || user.status() != UserStatus.ACTIVE
                || !java.util.Objects.equals(account.companyId(), companyId)
                || !java.util.Objects.equals(account.userId(), userId)
                || user.companyId() != companyId
                || !oauthSubject.subject().equals(subject)) {
            return State.INVALID;
        }
        return account.mustChangePassword() ? State.PASSWORD_CHANGE_REQUIRED : State.CURRENT;
    }

    @Transactional
    public State evaluateLocked(long accountId, long companyId, long userId, UUID subject) {
        var company = companies.findLockedById(companyId).orElse(null);
        var account = accounts.findByIdForUpdate(accountId).orElse(null);
        var user = users.findByIdForUpdate(userId).orElse(null);
        var oauthSubject = subjects.findByAccountId(accountId).orElse(null);
        if (account == null || company == null || user == null || oauthSubject == null
                || account.status() != AccountStatus.ACTIVE
                || account.lockedUntil() != null && account.lockedUntil().isAfter(clock.instant())
                || company.status() != CompanyStatus.ACTIVE
                || user.status() != UserStatus.ACTIVE
                || !java.util.Objects.equals(account.companyId(), companyId)
                || !java.util.Objects.equals(account.userId(), userId)
                || user.companyId() != companyId
                || !oauthSubject.subject().equals(subject)) {
            return State.INVALID;
        }
        return account.mustChangePassword() ? State.PASSWORD_CHANGE_REQUIRED : State.CURRENT;
    }
}
