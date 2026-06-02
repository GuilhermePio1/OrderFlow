CREATE TABLE payments (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    authorized_amount NUMERIC(19, 4) NOT NULL,
    captured_amount NUMERIC(19, 4) NOT NULL,
    refunded_amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    method VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    gateway_transaction_id VARCHAR(255),
    authorization_code VARCHAR(64),
    failure_reason VARCHAR(64),
    failure_details VARCHAR(512),
    initiated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,

    CONSTRAINT uq_payments_order_id UNIQUE (order_id),
    CONSTRAINT ck_payments_amounts_non_negative CHECK (
        authorized_amount > 0 AND captured_amount >= 0 AND refunded_amount >= 0
    ),
    CONSTRAINT ck_payments_captured_within_authorized CHECK (captured_amount <= authorized_amount),
    CONSTRAINT ck_payments_refunded_within_captured CHECK (refunded_amount <= captured_amount),
    CONSTRAINT ck_payments_version_non_negative CHECK (version >= 0)
);

CREATE INDEX idx_payments_customer_id ON payments (customer_id);

COMMENT ON TABLE payments IS
        'Estado transacional do agregado Payment (persistência tradicional, não event-sourced). '
        'Fonte de verdade do contexto Payment; eventos são publicados via Outbox/CDC.';
COMMENT ON COLUMN payments.order_id IS
        'Referência ao pedido. Único: garante idempotência — um pedido tem no máximo um pagamento.';
COMMENT ON COLUMN payments.version IS
        'Versão do agregado, base da concorrência otimista no UPDATE guardado por expected_version.';