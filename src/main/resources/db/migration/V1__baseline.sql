-- Baseline schema.
--
-- Shape of the model:
--   transactions                  header: identity, currency, gross total, timestamps
--   transaction_tax_lines         government/VAT-style charges, own table, ordered
--   transaction_fee_lines         carrier/merchant fees, own table, ordered
--   invoices                      invoice header with the totals frozen at build time
--   invoice_lines                 per-transaction SNAPSHOT (copied, never joined back)
--   invoice_line_tax_lines        snapshot of the transaction's tax lines
--   invoice_line_fee_lines        snapshot of the transaction's fee lines
--
-- Money: DECIMAL(19,2). Tax rate: DECIMAL(9,6) (a rate is not money).
-- Type-specific detail (flight coupons, hotel address, rail legs, fee kind) lives in
-- the validated JSON `metadata` document; everything we query, constrain, sum or
-- index is a real column.

CREATE TABLE transactions
(
    id          VARCHAR(48)              NOT NULL,
    external_id VARCHAR(190)             NOT NULL,
    type        VARCHAR(20)              NOT NULL,
    currency    CHAR(3)                  NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    total       DECIMAL(19, 2)           NOT NULL,
    metadata    CLOB                     NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_transactions PRIMARY KEY (id),
    CONSTRAINT ck_transactions_type CHECK (type IN ('flight', 'hotel', 'rail', 'navan_fee'))
);

-- The duplicate rule is a database guarantee, not an application check.
CREATE UNIQUE INDEX ux_transactions_external_id ON transactions (external_id);

-- Backs the paginated list (ORDER BY created_at DESC, id DESC) without a sort.
CREATE INDEX ix_transactions_created_at_id ON transactions (created_at DESC, id DESC);

CREATE TABLE transaction_tax_lines
(
    transaction_id VARCHAR(48)    NOT NULL,
    line_no        INT            NOT NULL,
    name           VARCHAR(120)   NOT NULL,
    rate           DECIMAL(9, 6),
    amount         DECIMAL(19, 2) NOT NULL,
    CONSTRAINT pk_transaction_tax_lines PRIMARY KEY (transaction_id, line_no),
    CONSTRAINT fk_transaction_tax_lines_transaction FOREIGN KEY (transaction_id)
        REFERENCES transactions (id) ON DELETE CASCADE
);

CREATE TABLE transaction_fee_lines
(
    transaction_id VARCHAR(48)    NOT NULL,
    line_no        INT            NOT NULL,
    name           VARCHAR(120)   NOT NULL,
    amount         DECIMAL(19, 2) NOT NULL,
    CONSTRAINT pk_transaction_fee_lines PRIMARY KEY (transaction_id, line_no),
    CONSTRAINT fk_transaction_fee_lines_transaction FOREIGN KEY (transaction_id)
        REFERENCES transactions (id) ON DELETE CASCADE
);

CREATE TABLE invoices
(
    id         VARCHAR(48)              NOT NULL,
    currency   CHAR(3)                  NOT NULL,
    total      DECIMAL(19, 2)           NOT NULL,
    tax_total  DECIMAL(19, 2)           NOT NULL,
    fee_total  DECIMAL(19, 2)           NOT NULL,
    line_count INT                      NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_invoices PRIMARY KEY (id)
);

CREATE INDEX ix_invoices_created_at_id ON invoices (created_at DESC, id DESC);

-- One row per invoiced transaction. Every billed value is COPIED here; reading an
-- invoice never touches the transactions table, so later edits cannot change it.
CREATE TABLE invoice_lines
(
    invoice_id     VARCHAR(48)              NOT NULL,
    line_no        INT                      NOT NULL,
    transaction_id VARCHAR(48)              NOT NULL,
    external_id    VARCHAR(190)             NOT NULL,
    type           VARCHAR(20)              NOT NULL,
    currency       CHAR(3)                  NOT NULL,
    occurred_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    total          DECIMAL(19, 2)           NOT NULL,
    metadata       CLOB                     NOT NULL,
    CONSTRAINT pk_invoice_lines PRIMARY KEY (invoice_id, line_no),
    -- No transaction can be billed twice on one invoice, enforced by the database.
    CONSTRAINT ux_invoice_lines_invoice_transaction UNIQUE (invoice_id, transaction_id),
    CONSTRAINT fk_invoice_lines_invoice FOREIGN KEY (invoice_id)
        REFERENCES invoices (id) ON DELETE CASCADE,
    -- Deliberately RESTRICT: a billed transaction cannot be deleted out from under
    -- an invoice even if the service-level guard were bypassed.
    CONSTRAINT fk_invoice_lines_transaction FOREIGN KEY (transaction_id)
        REFERENCES transactions (id)
);

-- Answers "is this transaction on any invoice?" with an index seek.
CREATE INDEX ix_invoice_lines_transaction_id ON invoice_lines (transaction_id);

CREATE TABLE invoice_line_tax_lines
(
    invoice_id VARCHAR(48)    NOT NULL,
    line_no    INT            NOT NULL,
    charge_no  INT            NOT NULL,
    name       VARCHAR(120)   NOT NULL,
    rate       DECIMAL(9, 6),
    amount     DECIMAL(19, 2) NOT NULL,
    CONSTRAINT pk_invoice_line_tax_lines PRIMARY KEY (invoice_id, line_no, charge_no),
    CONSTRAINT fk_invoice_line_tax_lines_line FOREIGN KEY (invoice_id, line_no)
        REFERENCES invoice_lines (invoice_id, line_no) ON DELETE CASCADE
);

CREATE TABLE invoice_line_fee_lines
(
    invoice_id VARCHAR(48)    NOT NULL,
    line_no    INT            NOT NULL,
    charge_no  INT            NOT NULL,
    name       VARCHAR(120)   NOT NULL,
    amount     DECIMAL(19, 2) NOT NULL,
    CONSTRAINT pk_invoice_line_fee_lines PRIMARY KEY (invoice_id, line_no, charge_no),
    CONSTRAINT fk_invoice_line_fee_lines_line FOREIGN KEY (invoice_id, line_no)
        REFERENCES invoice_lines (invoice_id, line_no) ON DELETE CASCADE
);
