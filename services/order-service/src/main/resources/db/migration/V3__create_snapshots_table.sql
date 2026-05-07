CREATE TABLE snapshots (
    aggregate_id UUID NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    sequence_number BIGINT NOT NULL,
    state JSONB NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_snapshots PRIMARY KEY (aggregate_id, sequence_number),
    CONSTRAINT ck_snapshots_sequence_positive CHECK (sequence_number > 0)
);

CREATE INDEX idx_snapshots_latest
    ON snapshots (aggregate_id, sequence_number DESC);

COMMENT ON TABLE snapshots IS
    'Otimização de leitura para agregados com muitos eventos. Não é fonte de verdade: '
    'pode ser regenerado a partir de events a qualquer momento.';
COMMENT ON COLUMN snapshots.sequence_number IS
    'Sequence_number do ultimo evento aplicado no estado snapshotado.';