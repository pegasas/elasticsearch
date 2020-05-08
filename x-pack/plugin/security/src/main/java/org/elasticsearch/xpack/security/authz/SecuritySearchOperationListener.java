/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.authz;

import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.index.shard.SearchOperationListener;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.search.SearchContextMissingException;
import org.elasticsearch.search.internal.ReaderContext;
import org.elasticsearch.search.internal.SearchContextId;
import org.elasticsearch.transport.TransportRequest;
import org.elasticsearch.xpack.core.security.SecurityContext;
import org.elasticsearch.xpack.core.security.authc.Authentication;
import org.elasticsearch.xpack.core.security.authc.AuthenticationField;
import org.elasticsearch.xpack.core.security.authz.AuthorizationEngine.AuthorizationInfo;
import org.elasticsearch.xpack.security.audit.AuditTrailService;
import org.elasticsearch.xpack.security.audit.AuditUtil;

import static org.elasticsearch.xpack.security.authz.AuthorizationService.AUTHORIZATION_INFO_KEY;
import static org.elasticsearch.xpack.security.authz.AuthorizationService.ORIGINATING_ACTION_KEY;

/**
 * A {@link SearchOperationListener} that is used to provide authorization for search requests.
 *
 * In order to identify the user associated with a search request, we put the {@link Authentication}
 * object in the {@link ReaderContext} on creation. When this context is accessed again in
 * {@link SearchOperationListener#validateSearchContext} the ReaderContext is inspected for
 * the authentication, which is compared to the currently authentication.
 */
public final class SecuritySearchOperationListener implements SearchOperationListener {

    private final SecurityContext securityContext;
    private final XPackLicenseState licenseState;
    private final AuditTrailService auditTrailService;

    public SecuritySearchOperationListener(SecurityContext securityContext, XPackLicenseState licenseState, AuditTrailService auditTrail) {
        this.securityContext = securityContext;
        this.licenseState = licenseState;
        this.auditTrailService = auditTrail;
    }

    @Override
    public void onNewReaderContext(ReaderContext readerContext) {
        if (licenseState.isSecurityEnabled()) {
            readerContext.putInContext(AuthenticationField.AUTHENTICATION_KEY, securityContext.getAuthentication());
        }
    }

    /**
     * compares the {@link Authentication} object from the reader context with the current
     * authentication context
     */
    @Override
    public void validateSearchContext(ReaderContext readerContext, TransportRequest request) {
        if (licenseState.isSecurityEnabled()) {
            final Authentication originalAuth = readerContext.getFromContext(AuthenticationField.AUTHENTICATION_KEY);
            final Authentication current = securityContext.getAuthentication();
            final ThreadContext threadContext = securityContext.getThreadContext();
            final String action = threadContext.getTransient(ORIGINATING_ACTION_KEY);
            ensureAuthenticatedUserIsSame(originalAuth, current, auditTrailService, readerContext.id(), action, request,
                AuditUtil.extractRequestId(threadContext), threadContext.getTransient(AUTHORIZATION_INFO_KEY));
        }
    }

    /**
     * Compares the {@link Authentication} that was stored in the {@link ReaderContext} with the
     * current authentication. We cannot guarantee that all of the details of the authentication will
     * be the same. Some things that could differ include the roles, the name of the authenticating
     * (or lookup) realm. To work around this we compare the username and the originating realm type.
     */
    static void ensureAuthenticatedUserIsSame(Authentication original, Authentication current, AuditTrailService auditTrailService,
                                              SearchContextId id, String action, TransportRequest request, String requestId,
                                              AuthorizationInfo authorizationInfo) {
        // this is really a best effort attempt since we cannot guarantee principal uniqueness
        // and realm names can change between nodes.
        final boolean samePrincipal = original.getUser().principal().equals(current.getUser().principal());
        final boolean sameRealmType;
        if (original.getUser().isRunAs()) {
            if (current.getUser().isRunAs()) {
                sameRealmType = original.getLookedUpBy().getType().equals(current.getLookedUpBy().getType());
            }  else {
                sameRealmType = original.getLookedUpBy().getType().equals(current.getAuthenticatedBy().getType());
            }
        } else if (current.getUser().isRunAs()) {
            sameRealmType = original.getAuthenticatedBy().getType().equals(current.getLookedUpBy().getType());
        } else {
            sameRealmType = original.getAuthenticatedBy().getType().equals(current.getAuthenticatedBy().getType());
        }

        final boolean sameUser = samePrincipal && sameRealmType;
        if (sameUser == false) {
            auditTrailService.get().accessDenied(requestId, current, action, request, authorizationInfo);
            throw new SearchContextMissingException(id);
        }
    }
}
