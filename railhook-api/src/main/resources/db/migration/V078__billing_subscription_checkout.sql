-- A paid checkout now creates its subscription row up front, in status PENDING, before the
-- customer is sent to the payment provider. The first successful payment callback finds the row
-- by the reference the provider echoes back and activates it. Until this, nothing outside the
-- tests ever created a billing_subscriptions row, so a customer could pay and stay on Free.
--
-- price_cents is what the checkout charged, in the currency's minor unit. Merchant-initiated
-- renewals (WayForPay) charge it again. They used to charge the catalog price, which is in USD
-- cents, in the subscription's currency, which for WayForPay is UAH: a 29 USD plan renewed at
-- 29 UAH. Nullable: a row without it renews at the catalog price, as before.
ALTER TABLE billing_subscriptions ADD COLUMN price_cents BIGINT;

COMMENT ON COLUMN billing_subscriptions.price_cents IS
    'Amount the checkout charged, in minor units of currency; renewals charge the same. NULL: catalog price.';

-- At most one subscription per organization that is paid for or being paid for. A second checkout
-- expires the pending one before it creates its own, and a checkout is refused while a live one
-- exists; this makes the database say so too, so two racing checkouts cannot both commit.
-- The table is written only by checkout, which never ran in production, so no existing
-- installation holds two such rows.
CREATE UNIQUE INDEX uq_billing_subs_one_open_per_org ON billing_subscriptions(organization_id)
    WHERE status IN ('PENDING', 'TRIALING', 'ACTIVE', 'PAST_DUE', 'GRACE_PERIOD');
