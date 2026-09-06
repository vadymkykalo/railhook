"""Guards the published identity of this SDK.

The PyPI distribution and the importable module are both ``railhook``, and
this file exists to keep them that way.

They used to disagree: the distribution was ``webhook-platform`` while the
module was ``hookflow``, so installing the SDK and importing it required
knowing two unrelated names. That mismatch is what the rename to Railhook was
for, and a test is the only thing that stops it drifting back — a rename that
touches one of the two and not the other reintroduces exactly the old problem,
and nothing else in the build would notice.
"""

import importlib.metadata

from railhook import Railhook

DISTRIBUTION = "railhook"


def test_distribution_and_module_are_both_railhook():
    dist = importlib.metadata.distribution(DISTRIBUTION)
    assert dist.metadata["Name"] == DISTRIBUTION
    assert Railhook.__module__.split(".")[0] == DISTRIBUTION


def test_smoke_import_of_railhook_module_constructs_a_client():
    client = Railhook(api_key="wh_test_key")
    assert isinstance(client, Railhook)
    assert client.events is not None
