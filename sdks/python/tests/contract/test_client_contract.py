"""Contract tests: run the python SDK against a REAL API instance and assert
its request/response shapes still match what the API actually does. The 71
cases in tests/test_*.py stub `requests` entirely — they'd stay green even
if the API renamed a field out from under this SDK. These exist to catch
that drift instead of a user finding it in production.

Run with: pytest tests/contract -v (requires CONTRACT_API_BASE_URL reachable
— defaults to http://localhost:8080, i.e. `make up`). See
tests/contract/README.md.

The repo now commits an OpenAPI spec (openapi.yaml at the repo root);
generating these expectations from the spec would be preferable to
hand-asserting field-by-field. This hand-asserted suite is the accepted
fallback until that generation exists.
"""
import pytest

from railhook import (
    Railhook,
    AuthenticationError,
    Event,
    EndpointCreateParams,
    SubscriptionCreateParams,
    DeliveryListParams,
)

from .support import BASE_URL, ContractContext

pytestmark = pytest.mark.contract


def make_client(ctx: ContractContext) -> Railhook:
    return Railhook(api_key=ctx.api_key, base_url=BASE_URL)


def test_endpoints_create_returns_the_shape_endpoint_declares(contract_ctx: ContractContext):
    client = make_client(contract_ctx)
    endpoint = client.endpoints.create(
        contract_ctx.project_id,
        EndpointCreateParams(url="https://example.com/webhook", description="contract test endpoint"),
    )

    assert isinstance(endpoint.id, str)
    assert endpoint.url == "https://example.com/webhook"
    assert isinstance(endpoint.enabled, bool)
    assert isinstance(endpoint.created_at, str)


def test_subscriptions_create_returns_the_shape_subscription_declares(contract_ctx: ContractContext):
    client = make_client(contract_ctx)
    endpoint = client.endpoints.create(
        contract_ctx.project_id, EndpointCreateParams(url="https://example.com/webhook2")
    )
    subscription = client.subscriptions.create(
        contract_ctx.project_id,
        SubscriptionCreateParams(
            endpoint_id=endpoint.id, event_type="contract.test.created", ordering_enabled=False
        ),
    )

    assert isinstance(subscription.id, str)
    assert subscription.endpoint_id == endpoint.id
    assert subscription.event_type == "contract.test.created"
    assert isinstance(subscription.enabled, bool)
    assert isinstance(subscription.max_attempts, int)


def test_events_send_accepted_and_fans_out(contract_ctx: ContractContext):
    client = make_client(contract_ctx)
    endpoint = client.endpoints.create(
        contract_ctx.project_id, EndpointCreateParams(url="https://example.com/webhook3")
    )
    client.subscriptions.create(
        contract_ctx.project_id,
        SubscriptionCreateParams(endpoint_id=endpoint.id, event_type="contract.test.event_send"),
    )

    response = client.events.send(Event(type="contract.test.event_send", data={"hello": "world"}))

    assert isinstance(response.event_id, str)
    assert response.type == "contract.test.event_send"
    assert response.deliveries_created == 1


def test_deliveries_list_returns_a_paginated_response(contract_ctx: ContractContext):
    client = make_client(contract_ctx)
    page = client.deliveries.list(contract_ctx.project_id, DeliveryListParams(size=5))

    assert isinstance(page.content, list)
    assert isinstance(page.total_elements, int)


def test_invalid_api_key_is_rejected_as_401(contract_ctx: ContractContext):
    bad_client = Railhook(api_key="not-a-real-key", base_url=BASE_URL)
    try:
        bad_client.events.send(Event(type="contract.test.bad_key", data={}))
        assert False, "expected AuthenticationError"
    except AuthenticationError as e:
        assert e.status == 401
