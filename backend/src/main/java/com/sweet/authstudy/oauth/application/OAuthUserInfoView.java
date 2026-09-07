package com.sweet.authstudy.oauth.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, claim-shaped application result. Protocol maps are created only at the SAS adapter boundary.
 */
public record OAuthUserInfoView(
        String subject,
        Optional<Profile> profile,
        Optional<Email> email,
        Optional<Company> company,
        Optional<Organization> organization,
        Optional<List<String>> roles) {

    public OAuthUserInfoView {
        subject = requireText(subject, "subject");
        profile = Objects.requireNonNull(profile, "profile");
        email = Objects.requireNonNull(email, "email");
        company = Objects.requireNonNull(company, "company");
        organization = Objects.requireNonNull(organization, "organization");
        roles = Objects.requireNonNull(roles, "roles").map(List::copyOf);
    }

    public record Profile(String name) {
        public Profile {
            name = requireText(name, "name");
        }
    }

    public record Email(Optional<String> value, boolean verified) {
        public Email {
            value = Objects.requireNonNull(value, "value").filter(candidate -> !candidate.isBlank());
        }
    }

    public record Company(String code, String name) {
        public Company {
            code = requireText(code, "company code");
            name = requireText(name, "company name");
        }
    }

    public record CodeName(String code, String name) {
        public CodeName {
            code = requireText(code, "code");
            name = requireText(name, "name");
        }
    }

    public record Organization(
            Optional<CodeName> position,
            Optional<CodeName> primaryDepartment,
            List<CodeName> secondaryDepartments) {
        public Organization {
            position = Objects.requireNonNull(position, "position");
            primaryDepartment = Objects.requireNonNull(primaryDepartment, "primaryDepartment");
            secondaryDepartments = List.copyOf(
                    Objects.requireNonNull(secondaryDepartments, "secondaryDepartments"));
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        return value;
    }
}
