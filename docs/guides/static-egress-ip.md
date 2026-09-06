# Giving your customers a fixed IP to allowlist

A recurring request from anyone whose receivers sit behind a corporate firewall: *"what IP will
your webhooks come from, so we can allowlist it?"*

Railhook has no application-level setting for this. There is no forward-proxy configuration on
the worker's HTTP client, and setting `HTTP_PROXY` in the environment will not be honoured — the
delivery client is Reactor Netty and does not read those variables. Anyone telling you otherwise
has not tried it.

What *does* work is solving it one layer down, at the network. Because the worker is the only
component that makes outbound delivery requests, pinning its egress pins every webhook you send.

## The shape of the problem

```
worker pod  →  ???  →  your customer's endpoint
                ↑
        this is the address they see
```

By default that address is whatever your infrastructure happens to use — a node's public IP, a
cloud NAT pool, a dynamic address that changes when a node is replaced. None of those are
allowlistable.

## Option 1 — Cloud NAT gateway (most deployments)

Every major cloud offers a managed NAT with a static address. Put the worker's subnet behind it
and every outbound connection presents that address.

| Cloud | Service | What to attach |
|---|---|---|
| AWS | NAT Gateway | An Elastic IP, in the worker nodes' private subnet route table |
| GCP | Cloud NAT | A reserved static external IP |
| Azure | NAT Gateway | A static Public IP |
| Hetzner / DO / bare metal | A router or gateway host | A floating IP, with the worker's default route through it |

This is the option to reach for first. It needs no Railhook configuration, survives node
replacement, and gives you one or two addresses to publish.

Two addresses is usually the right number, not one: a single NAT is a single point of failure,
and customers who allowlist find a second address far easier to add up front than during an
outage.

## Option 2 — A dedicated egress node pool

If only some traffic should carry the fixed address, schedule the **worker** onto a node pool
that sits behind the NAT and leave the API elsewhere. The chart makes this straightforward — the
worker deployment takes `nodeSelector` and `tolerations` through values, and the API is a
separate deployment.

This is also the answer when the fixed address is expensive or rate-limited and you do not want
dashboard traffic, backups and image pulls sharing it.

## Option 3 — An egress proxy in the mesh

If you already run a service mesh or an egress controller (Istio egress gateway, Cilium egress
gateway, a Squid or Smokescreen host with an iptables redirect), route the worker's traffic
through it. The application still knows nothing about the proxy — the redirect happens below it,
which is precisely why this works without a Railhook setting.

A side benefit: an egress proxy is a natural place to log or restrict outbound destinations,
which pairs well with the SSRF protection Railhook already applies before a request is built.

## What to tell your customers

Publish the addresses somewhere they can read without asking, and say two things:

1. **Which addresses**, plural, and that both may be used.
2. **That the list can change**, with how much notice. An allowlist you change silently is an
   outage you caused on someone else's infrastructure.

Note that inbound and outbound are different problems. If your customers send *to* Railhook —
the incoming direction — they do not need this at all; they need your ingress URL. And if you
want to restrict who may reach one of your Endpoints, that is `allowedSourceIps` on the Endpoint,
which is a separate feature and is enforced by Railhook rather than by your network.

## Related

- **SSRF protection.** Every delivery URL is validated before a request is built; private ranges
  are refused unless explicitly allowed. An egress proxy does not replace that check.
- **`ROADMAP.md`** tracks static egress IPs as a known gap at the application level. Everything
  above is a network-layer workaround, and a good one, but it is a workaround.
