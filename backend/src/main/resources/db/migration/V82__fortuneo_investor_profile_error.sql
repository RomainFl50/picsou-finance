ALTER TABLE fortuneo_session
    DROP CONSTRAINT ck_fortuneo_session_last_sync_error;

ALTER TABLE fortuneo_session
    ADD CONSTRAINT ck_fortuneo_session_last_sync_error
        CHECK (
            last_sync_error IS NULL
            OR last_sync_error IN (
                'INVALID_CREDENTIALS',
                'INVALID_OTP',
                'AUTH_ATTEMPT_EXPIRED',
                'SESSION_EXPIRED',
                'INVESTOR_PROFILE_REQUIRED',
                'PORTFOLIO_INCOMPLETE',
                'UPSTREAM_FORMAT_CHANGED',
                'UPSTREAM_UNAVAILABLE',
                'INVALID_DATA',
                'INTERNAL_ERROR'
            )
        );
