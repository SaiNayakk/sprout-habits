-- Habits are worked out from trading history each time; only what customers choose is stored here.
CREATE TABLE privacy (
    user_id              uuid PRIMARY KEY,
    show_invested_range  boolean NOT NULL
);

CREATE TABLE readiness (
    user_id                uuid PRIMARY KEY,
    emergency_fund_months  int NOT NULL,
    high_interest_debt     boolean NOT NULL,
    horizon_years          int NOT NULL,
    ready                  boolean NOT NULL,
    advice                 text NOT NULL,      -- JSON array of strings
    checked_at             timestamptz NOT NULL
);

CREATE TABLE squads (
    id           uuid PRIMARY KEY,
    name         text NOT NULL,
    invite_code  text NOT NULL UNIQUE,
    created_by   uuid NOT NULL,
    created_at   timestamptz NOT NULL
);

CREATE TABLE members (
    squad_id   uuid NOT NULL REFERENCES squads (id),
    user_id    uuid NOT NULL,
    nickname   text NOT NULL,
    joined_at  timestamptz NOT NULL,
    PRIMARY KEY (squad_id, user_id)
);

CREATE INDEX members_by_user ON members (user_id);
