DROP VIEW account_sync_status;

ALTER TABLE account_sync_event DROP COLUMN payload;
ALTER TABLE account_sync_event ADD COLUMN cursor JSONB;
ALTER TABLE account_sync_event ADD COLUMN error JSONB;

CREATE VIEW account_sync_status AS (
    SELECT DISTINCT ON (account_id)
        account_id,
        "key",
        coin_family,
        coin,
        sync_frequency,
        sync_id,
        status,
        "cursor",
        error,
        updated,
        label
    FROM account_info JOIN account_sync_event USING (account_id)
    ORDER BY account_id, updated DESC
);
