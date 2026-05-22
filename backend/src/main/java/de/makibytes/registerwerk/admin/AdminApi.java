package de.makibytes.registerwerk.admin;

import de.makibytes.registerwerk.admin.web.dto.OperatorUserResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

/** Public API for operator user and impersonation management. */
public interface AdminApi {

    Page<OperatorUserResponse> listOperatorUsers(String search, String role, String status, Pageable pageable);
}
