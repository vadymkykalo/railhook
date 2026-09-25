"""The distribution and the module once had unrelated names; this keeps both ``railhook``."""

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
