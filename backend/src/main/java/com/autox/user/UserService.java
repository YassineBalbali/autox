package com.autox.user;

import com.autox.api.error.ApiException;
import com.autox.reference.CurrencyRepository;
import com.autox.user.dto.UpdateProfileRequest;
import com.autox.user.dto.UserProfileResponse;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Own-profile reads and updates. */
@Service
public class UserService {

    private final AppUserRepository users;
    private final ProProfileRepository proProfiles;
    private final CurrencyRepository currencies;

    public UserService(AppUserRepository users, ProProfileRepository proProfiles, CurrencyRepository currencies) {
        this.users = users;
        this.proProfiles = proProfiles;
        this.currencies = currencies;
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(UUID userId) {
        AppUser user = require(userId);
        return UserProfileResponse.from(user, proProfiles.findByUserId(userId).orElse(null));
    }

    @Transactional
    public UserProfileResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        AppUser user = require(userId);

        if (request.displayName() != null) {
            user.setDisplayName(request.displayName().trim());
        }
        if (request.firstName() != null) {
            user.setFirstName(blankToNull(request.firstName()));
        }
        if (request.lastName() != null) {
            user.setLastName(blankToNull(request.lastName()));
        }
        if (request.phone() != null) {
            String phone = blankToNull(request.phone());
            // Changing the number invalidates any previous verification of it.
            if (phone == null || !phone.equals(user.getPhoneE164())) {
                user.setPhoneVerifiedAt(null);
            }
            user.setPhoneE164(phone);
        }
        if (request.locale() != null) {
            user.setLocale(request.locale());
        }
        if (request.preferredCurrencyCode() != null) {
            String code = blankToNull(request.preferredCurrencyCode());
            user.setPreferredCurrency(code == null ? null : currencies.findById(code)
                    .orElseThrow(() -> ApiException.validationFailed(
                            "preferredCurrencyCode '" + code + "' is not a supported currency.")));
        }

        if (request.professional() != null) {
            applyProfessional(user, request.professional());
        }

        return UserProfileResponse.from(user, proProfiles.findByUserId(userId).orElse(null));
    }

    private void applyProfessional(AppUser user, UpdateProfileRequest.ProProfileUpdate update) {
        if (user.getRole() != UserRole.PROFESSIONNEL) {
            throw ApiException.forbidden("Only a PROFESSIONNEL account has a company profile.");
        }

        ProProfile profile = proProfiles.findByUserId(user.getId())
                .orElseThrow(() -> ApiException.notFound("Company profile"));

        if (update.companyName() != null) {
            profile.setCompanyName(update.companyName().trim());
        }
        if (update.legalForm() != null) {
            profile.setLegalForm(blankToNull(update.legalForm()));
        }
        if (update.registrationNumber() != null) {
            profile.setRegistrationNumber(blankToNull(update.registrationNumber()));
        }
        if (update.vatNumber() != null) {
            profile.setVatNumber(blankToNull(update.vatNumber()));
        }
        if (update.websiteUrl() != null) {
            profile.setWebsiteUrl(blankToNull(update.websiteUrl()));
        }
        if (update.description() != null) {
            profile.setDescription(blankToNull(update.description()));
        }
        if (update.addressLine1() != null) {
            profile.setAddressLine1(blankToNull(update.addressLine1()));
        }
        if (update.addressLine2() != null) {
            profile.setAddressLine2(blankToNull(update.addressLine2()));
        }
        if (update.contactPhone() != null) {
            profile.setContactPhoneE164(blankToNull(update.contactPhone()));
        }
    }

    private AppUser require(UUID userId) {
        return users.findActiveById(userId).orElseThrow(() -> ApiException.notFound("User"));
    }

    private static String blankToNull(String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
