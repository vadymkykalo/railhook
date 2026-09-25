from unittest import mock

import pytest

from railhook import (
    Railhook,
    Consumer,
    ConsumerCreateParams,
    ConsumerUpdateParams,
    EndpointCreateParams,
    EndpointUpdateParams,
    PortalSession,
    PortalSessionCreateParams,
)

PROJECT_ID = "proj-123"
CONSUMER_ID = "con-456"

CONSUMER = {
    "id": CONSUMER_ID,
    "projectId": PROJECT_ID,
    "externalId": "user-42",
    "name": "Acme Ltd",
    "endpointCount": 2,
    "createdAt": "2024-01-01T00:00:00Z",
}


def _response(status: int, body=None):
    response = mock.Mock()
    response.status_code = status
    response.headers = {}
    response.text = "" if body is None else "x"
    response.json.return_value = body
    return response


@pytest.fixture
def client():
    return Railhook(api_key="test_key", base_url="http://api.test")


def _call(request):
    kwargs = request.call_args.kwargs
    return kwargs["method"], kwargs["url"], kwargs.get("json"), kwargs.get("params")


class TestConsumers:
    def test_create_posts_external_id_and_name(self, client):
        with mock.patch("railhook.client.requests.request", return_value=_response(201, CONSUMER)) as request:
            consumer = client.consumers.create(
                PROJECT_ID, ConsumerCreateParams(external_id="user-42", name="Acme Ltd")
            )

        method, url, body, _ = _call(request)
        assert method == "POST"
        assert url == f"http://api.test/api/v1/projects/{PROJECT_ID}/consumers"
        assert body == {"externalId": "user-42", "name": "Acme Ltd"}
        assert request.call_args.kwargs["headers"]["X-API-Key"] == "test_key"
        assert isinstance(consumer, Consumer)
        assert consumer.external_id == "user-42"
        assert consumer.endpoint_count == 2

    def test_create_without_name_omits_it(self):
        assert ConsumerCreateParams(external_id="user-42").to_dict() == {"externalId": "user-42"}

    def test_list_filters_by_external_id(self, client):
        page = {"content": [CONSUMER], "totalElements": 1, "totalPages": 1, "size": 20, "number": 0}
        with mock.patch("railhook.client.requests.request", return_value=_response(200, page)) as request:
            result = client.consumers.list(PROJECT_ID, external_id="user-42")

        method, url, _, params = _call(request)
        assert method == "GET"
        assert url == f"http://api.test/api/v1/projects/{PROJECT_ID}/consumers"
        assert params == {"page": 0, "size": 20, "externalId": "user-42"}
        assert [c.id for c in result] == [CONSUMER_ID]

    def test_get_update_delete_address_the_consumer(self, client):
        path = f"http://api.test/api/v1/projects/{PROJECT_ID}/consumers/{CONSUMER_ID}"
        with mock.patch("railhook.client.requests.request", return_value=_response(200, CONSUMER)) as request:
            client.consumers.get(PROJECT_ID, CONSUMER_ID)
            assert _call(request)[:2] == ("GET", path)

            client.consumers.update(
                PROJECT_ID, CONSUMER_ID, ConsumerUpdateParams(external_id="user-42", name="Acme Inc")
            )
            assert _call(request)[:3] == ("PUT", path, {"externalId": "user-42", "name": "Acme Inc"})

        with mock.patch("railhook.client.requests.request", return_value=_response(204)) as request:
            assert client.consumers.delete(PROJECT_ID, CONSUMER_ID) is None
            assert _call(request)[:2] == ("DELETE", path)

    def test_list_endpoints(self, client):
        endpoint = {
            "id": "ep-1",
            "url": "https://example.com/hook",
            "enabled": True,
            "createdAt": "2024-01-01T00:00:00Z",
            "consumerId": CONSUMER_ID,
        }
        with mock.patch("railhook.client.requests.request", return_value=_response(200, [endpoint])) as request:
            endpoints = client.consumers.list_endpoints(PROJECT_ID, CONSUMER_ID)

        assert _call(request)[:2] == (
            "GET",
            f"http://api.test/api/v1/projects/{PROJECT_ID}/consumers/{CONSUMER_ID}/endpoints",
        )
        assert endpoints[0].consumer_id == CONSUMER_ID

    def test_endpoint_params_carry_consumer_id(self):
        assert EndpointCreateParams(url="https://x.test", consumer_id=CONSUMER_ID).to_dict()["consumerId"] == CONSUMER_ID
        assert EndpointUpdateParams(consumer_id=CONSUMER_ID).to_dict() == {"consumerId": CONSUMER_ID}
        assert "consumerId" not in EndpointCreateParams(url="https://x.test").to_dict()


class TestPortalSessions:
    SESSION = {
        "id": "ps-1",
        "consumerId": CONSUMER_ID,
        "url": "https://railhook.test/portal?origin=https://app.example.com#rhp_abc",
        "token": "rhp_abc",
        "allowedOrigin": "https://app.example.com",
        "expiresAt": "2024-01-01T01:00:00Z",
    }

    def test_create_posts_ttl_and_origin(self, client):
        with mock.patch("railhook.client.requests.request", return_value=_response(201, self.SESSION)) as request:
            session = client.portal_sessions.create(
                PROJECT_ID,
                CONSUMER_ID,
                PortalSessionCreateParams(ttl_minutes=30, allowed_origin="https://app.example.com"),
            )

        method, url, body, _ = _call(request)
        assert method == "POST"
        assert url == f"http://api.test/api/v1/projects/{PROJECT_ID}/consumers/{CONSUMER_ID}/portal-sessions"
        assert body == {"ttlMinutes": 30, "allowedOrigin": "https://app.example.com"}
        assert isinstance(session, PortalSession)
        assert session.token == "rhp_abc"
        assert session.allowed_origin == "https://app.example.com"

    def test_create_without_params_sends_empty_body(self, client):
        with mock.patch("railhook.client.requests.request", return_value=_response(201, self.SESSION)) as request:
            client.portal_sessions.create(PROJECT_ID, CONSUMER_ID)
        assert _call(request)[2] == {}

    def test_revoke_deletes_the_consumers_sessions(self, client):
        with mock.patch("railhook.client.requests.request", return_value=_response(204)) as request:
            client.portal_sessions.revoke(PROJECT_ID, CONSUMER_ID)
        assert _call(request)[:2] == (
            "DELETE",
            f"http://api.test/api/v1/projects/{PROJECT_ID}/consumers/{CONSUMER_ID}/portal-sessions",
        )
