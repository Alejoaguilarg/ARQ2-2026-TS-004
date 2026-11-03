"""Builds the Finova C4 diagrams (level 1 and level 2) as HTML pages; render.cjs turns them into PNG.

Regenerate both images after editing the nodes or edges below (Python 3 and Node with Playwright):

    cd docs/assets/c4-src && python3 c4.py . && node render.cjs

render.cjs writes ../finova-C4-context.png (level 1) and ../finova-C4.png (level 2).
"""
import html
import sys

OUT = sys.argv[1]

COLORS = {
    'person': ('#93c4f5', '#0b1d33'),
    'system': ('#1f6fb8', '#ffffff'),
    'container': ('#3f84c9', '#ffffff'),
    'client': ('#2a5e9e', '#ffffff'),
    'db': ('#1b8bbd', '#ffffff'),
    'redis': ('#f2867a', '#2a0c08'),
    'broker': ('#c2672d', '#ffffff'),
    'external': ('#7a7487', '#ffffff'),
    'obs': ('#4b4f63', '#ffffff'),
}

CSS = """
* { box-sizing: border-box; }
body { margin: 0; background: #121212; font-family: Inter, 'Helvetica Neue', Arial, sans-serif; }
.canvas { position: relative; }
h1 { position: absolute; left: 0; right: 0; top: 18px; margin: 0; text-align: center; color: #e8e8e8;
     font-size: 44px; font-weight: 700; letter-spacing: -0.5px; }
.node { position: absolute; border-radius: 14px; padding: 12px 16px; display: flex; flex-direction: column;
        align-items: center; justify-content: center; text-align: center; gap: 4px; }
.node .t { font-size: 19px; font-weight: 700; }
.node .k { font-size: 12.5px; opacity: .85; }
.node .d { font-size: 13.5px; line-height: 1.35; margin-top: 4px; }
.person { border-radius: 40px; padding-top: 70px; }
.person::before { content: ''; position: absolute; top: -48px; left: 50%; width: 92px; height: 92px; margin-left: -46px;
                  border-radius: 50%; background: inherit; border: 3px solid #121212; }
.db { border-radius: 50% / 18%; padding-top: 34px; }
.boundary { position: absolute; border: 2px dashed #8a8a8a; border-radius: 14px; }
.boundary span { position: absolute; left: 14px; bottom: 8px; color: #d0d0d0; font-size: 15px; }
.boundary span b { font-weight: 700; }
.label { position: absolute; color: #d8d8d8; font-size: 12.5px; line-height: 1.25; text-align: center;
         background: #121212; padding: 1px 4px; transform: translate(-50%, -50%); white-space: nowrap; }
.label i { color: #a8a8a8; }
.legend { position: absolute; border: 1px solid #555; border-radius: 6px; padding: 12px 14px; color: #ddd;
          font-size: 13px; line-height: 1.5; }
.legend b { display: block; margin-bottom: 4px; }
.sw { display: inline-block; width: 12px; height: 12px; border-radius: 3px; margin-right: 6px; vertical-align: -1px; }
"""


def node(n):
    bg, fg = COLORS[n['kind']]
    cls = 'node' + (' person' if n['kind'] == 'person' else '') + (' db' if n['kind'] == 'db' else '')
    desc = f"<div class='d'>{html.escape(n.get('desc', ''))}</div>" if n.get('desc') else ''
    return (f"<div class='{cls}' style='left:{n['x']}px;top:{n['y']}px;width:{n['w']}px;height:{n['h']}px;"
            f"background:{bg};color:{fg}'><div class='t'>{html.escape(n['title'])}</div>"
            f"<div class='k'>[{html.escape(n['tech'])}]</div>{desc}</div>")


def anchor(n, side, t=0.5):
    x, y, w, h = n['x'], n['y'], n['w'], n['h']
    return {'t': (x + w * t, y), 'b': (x + w * t, y + h), 'l': (x, y + h * t), 'r': (x + w, y + h * t)}[side]


def page(title, width, height, nodes, edges, boundary=None, legend=None):
    by_id = {n['id']: n for n in nodes}
    paths, labels = [], []
    for e in edges:
        a = anchor(by_id[e['from']], e['fs'], e.get('ft', 0.5))
        b = anchor(by_id[e['to']], e['ts'], e.get('tt', 0.5))
        pts = [a] + [tuple(p) for p in e.get('via', [])] + [b]
        d = 'M ' + ' L '.join(f'{x:.0f} {y:.0f}' for x, y in pts)
        dash = " stroke-dasharray='7 6'" if e.get('dashed') else ''
        both = " marker-start='url(#arrow-start)'" if e.get('both') else ''
        paths.append(f"<path d='{d}' fill='none' stroke='#bdbdbd' stroke-width='1.8' marker-end='url(#arrow)'{both}{dash}/>")
        if e.get('label'):
            lx, ly = e.get('at') or ((pts[0][0] + pts[1][0]) / 2, (pts[0][1] + pts[1][1]) / 2)
            text = '<br>'.join(html.escape(part) for part in e['label'].split('\n'))
            tech = f"<br><i>[{html.escape(e['tech'])}]</i>" if e.get('tech') else ''
            labels.append(f"<div class='label' style='left:{lx}px;top:{ly}px'>{text}{tech}</div>")
    svg = (f"<svg width='{width}' height='{height}' style='position:absolute;left:0;top:0'>"
           "<defs><marker id='arrow' viewBox='0 0 10 10' refX='9' refY='5' markerWidth='8' markerHeight='8' orient='auto'>"
           "<path d='M0 0 L10 5 L0 10 z' fill='#bdbdbd'/></marker>"
           "<marker id='arrow-start' viewBox='0 0 10 10' refX='1' refY='5' markerWidth='8' markerHeight='8' orient='auto'>"
           "<path d='M10 0 L0 5 L10 10 z' fill='#bdbdbd'/></marker></defs>" + ''.join(paths) + '</svg>')
    bnd = ''
    if boundary:
        x, y, w, h, name, kind = boundary
        bnd = (f"<div class='boundary' style='left:{x}px;top:{y}px;width:{w}px;height:{h}px'>"
               f"<span><b>{html.escape(name)}</b> [{html.escape(kind)}]</span></div>")
    leg = ''
    if legend:
        x, y, w, items, note = legend
        rows = ''.join(f"<div><span class='sw' style='background:{COLORS[k][0]}'></span>{html.escape(t)}</div>" for k, t in items)
        leg = (f"<div class='legend' style='left:{x}px;top:{y}px;width:{w}px'><b>Legend</b>{rows}"
               f"<div style='margin-top:8px'>{html.escape(note)}</div></div>")
    return (f"<!doctype html><html><head><meta charset='utf-8'><style>{CSS}</style></head><body>"
            f"<div class='canvas' style='width:{width}px;height:{height}px'><h1>{html.escape(title)}</h1>"
            f"{bnd}{svg}{''.join(node(n) for n in nodes)}{''.join(labels)}{leg}</div></body></html>")


# ---------------------------------------------------------------- level 1: system context
L1 = [
    dict(id='user', kind='person', x=140, y=190, w=330, h=190, title='Wallet User', tech='Person',
         desc='Checks the balance and sends money to other users by email.'),
    dict(id='analyst', kind='person', x=1130, y=190, w=330, h=190, title='Operations / Compliance Analyst',
         tech='Person', desc='Reviews every transfer attempt and checks that the audit trail is intact.'),
    dict(id='finova', kind='system', x=520, y=500, w=560, h=210, title='Finova P2P Virtual Wallet', tech='Software System',
         desc='Sign-in with step-up (PIN or OTP), balance, P2P transfers that are never charged twice and are '
              'reversed automatically when they cannot complete, and an append-only, hash-chained audit trail.'),
    dict(id='provider', kind='external', x=560, y=860, w=480, h=150, title='Email / SMS / Push Provider',
         tech='External Software System', desc='Delivers the transfer outcome notifications.'),
]
L1_EDGES = [
    dict(**{'from': 'user', 'to': 'finova'}, fs='b', ts='t', tt=0.2, via=[(305, 450), (632, 450)],
         label='Signs in, checks the balance,\nsends money', tech='HTTPS, web browser', at=(305, 440)),
    dict(**{'from': 'analyst', 'to': 'finova'}, fs='b', ts='t', tt=0.8, via=[(1295, 450), (968, 450)],
         label='Queries attempts,\nverifies the audit chain', tech='HTTPS, web browser', at=(1295, 440)),
    dict(**{'from': 'finova', 'to': 'provider'}, fs='b', ts='t', label='Sends transfer outcome notifications',
         tech='HTTPS', at=(800, 785)),
    dict(**{'from': 'provider', 'to': 'user'}, fs='l', ts='b', tt=0.3, via=[(240, 935)],
         label='Delivers email, SMS or push', at=(240, 640)),
]

# ---------------------------------------------------------------- level 2: containers
SX = [80, 480, 880, 1280, 1680]          # service columns
SW, SY, SH = 340, 830, 160
DY, DW, DH = 1075, 270, 140
L2 = [
    dict(id='user', kind='person', x=330, y=140, w=330, h=165, title='Wallet User', tech='Person',
         desc='Checks the balance and sends money.'),
    dict(id='analyst', kind='person', x=1230, y=140, w=330, h=165, title='Operations / Compliance Analyst',
         tech='Person', desc='Reviews the audit trail (role AUDITOR).'),
    dict(id='web', kind='client', x=720, y=370, w=470, h=150, title='Web App', tech='Container: React SPA served by nginx',
         desc='Sign-in, balance, send by email with PIN, live tracking, and the Activity screen for auditors. '
              'nginx proxies /api to the Gateway.'),
    dict(id='redis', kind='redis', x=80, y=580, w=420, h=170, title='Redis', tech='Container: Redis 7 + replica',
         desc='Idempotency records (SET NX, 24 h), revoked JTIs and sessions, single-use financial sessions, '
              'rate-limit and lockout counters, balance read model (versioned).'),
    dict(id='gw', kind='container', x=720, y=590, w=470, h=150, title='API Gateway',
         tech='Container: Java 21, Spring Cloud Gateway MVC',
         desc='Single entry point. JWT (JWKS), financial session, mandatory Idempotency-Key, rate limits, '
              'lockouts, routing of the 10 public endpoints.'),
    dict(id='obs', kind='obs', x=1300, y=575, w=720, h=180, title='Observability', tech='Containers: Prometheus, Loki, Tempo, Alloy, Grafana',
         desc='Prometheus scrapes /actuator/prometheus (Gateway on its internal management port). Alloy ships the '
              'container logs to Loki and the OTLP traces to Tempo. Grafana: dashboard, alerts, log to trace links.'),
    dict(id='identity', kind='container', x=SX[0], y=SY, w=SW, h=SH, title='Identity Service', tech='Container: Java 21, Spring Boot',
         desc='Users, devices, login, JWT (15 min) + rotating refresh, step-up (PIN / OTP), JWKS, user lookup.'),
    dict(id='wallet', kind='container', x=SX[1], y=SY, w=SW, h=SH, title='Wallet Service', tech='Container: Java 21, Spring Boot',
         desc='Balance, conditional atomic debit, credit, reversal, ledger. Credit or reversal, never both.'),
    dict(id='transfer', kind='container', x=SX[2], y=SY, w=SW, h=SH, title='Transfer Service', tech='Container: Java 21, Spring Boot',
         desc='Transfer state machine, saga orchestrator with timeouts and compensation, transactional outbox.'),
    dict(id='audit', kind='container', x=SX[3], y=SY, w=SW, h=SH, title='Audit Service', tech='Container: Java 21, Spring Boot',
         desc='Stores every transfer attempt and outcome, append-only with SHA-256 hash chaining; verify endpoint.'),
    dict(id='notif', kind='container', x=SX[4], y=SY, w=SW, h=SH, title='Notification Service', tech='Container: Java 21, Spring Boot',
         desc='Notifies transfer outcomes from events, with retries and DLQ. Never in the critical path.'),
    dict(id='identitydb', kind='db', x=SX[0], y=DY, w=DW, h=DH, title='Identity DB', tech='PostgreSQL 16',
         desc='users, devices, refresh_tokens'),
    dict(id='walletdb', kind='db', x=SX[1], y=DY, w=DW, h=DH, title='Wallet DB', tech='PostgreSQL 16',
         desc='wallets, ledger_entries, transfer_settlements, outbox, processed_messages'),
    dict(id='transferdb', kind='db', x=SX[2], y=DY, w=DW, h=DH, title='Transfer DB', tech='PostgreSQL 16',
         desc='transfers, outbox, processed_messages'),
    dict(id='auditdb', kind='db', x=SX[3], y=DY, w=DW, h=DH, title='Audit DB', tech='PostgreSQL 16',
         desc='audit_records (append-only, monthly partitions), audit_event_ids'),
    dict(id='notifdb', kind='db', x=SX[4], y=DY, w=DW, h=DH, title='Notification DB', tech='PostgreSQL 16',
         desc='recipient_contacts, notification_log'),
    dict(id='broker', kind='broker', x=80, y=1350, w=1940, h=110, title='Message Broker', tech='Container: RabbitMQ 4',
         desc='Exchanges wallet.commands, wallet.events, transfer.events, audit.events. Durable quorum queues, '
              'publisher confirms, at-least-once delivery, retry queue and DLQ per queue.'),
    dict(id='provider', kind='external', x=2130, y=830, w=330, h=160, title='Email / SMS / Push Provider',
         tech='External Software System', desc='Delivers the notifications (a WireMock stands in for it in docker-compose).'),
]
GW = next(n for n in L2 if n['id'] == 'gw')


def to_x(service):
    return next(n['x'] + n['w'] / 2 for n in L2 if n['id'] == service)


L2_EDGES = [
    dict(**{'from': 'user', 'to': 'web'}, fs='b', ts='t', tt=0.25, via=[(495, 340), (837, 340)],
         label='Uses', tech='HTTPS, browser', at=(495, 335)),
    dict(**{'from': 'analyst', 'to': 'web'}, fs='b', ts='t', tt=0.75, via=[(1395, 340), (1072, 340)],
         label='Uses (role AUDITOR)', tech='HTTPS, browser', at=(1395, 335)),
    dict(**{'from': 'web', 'to': 'gw'}, fs='b', ts='t', label='/api: Bearer JWT, Idempotency-Key,\nX-Financial-Session',
         tech='HTTPS/JSON via nginx', at=(955, 555)),
    dict(**{'from': 'gw', 'to': 'redis'}, fs='l', ts='r', label='Idempotency, revocation,\nlimits, sessions', tech='RESP',
         at=(610, 665)),
    dict(**{'from': 'gw', 'to': 'identity'}, fs='b', ft=0.05, ts='t', tt=0.7, via=[(743.5, 800), (318, 800)],
         label='Login, refresh, logout,\nstep-up, lookup', tech='HTTPS/REST', at=(640, 800)),
    dict(**{'from': 'gw', 'to': 'wallet'}, fs='b', ft=80 / 470, ts='t', tt=320 / 340,
         label='GET balance', tech='HTTPS/REST', at=(800, 772)),
    dict(**{'from': 'gw', 'to': 'transfer'}, fs='b', ft=0.45, ts='t', label='POST /transfers, GET status',
         tech='HTTPS/REST', at=(1000, 785)),
    dict(**{'from': 'gw', 'to': 'audit'}, fs='b', ft=0.85, ts='t', tt=0.4, via=[(1120, 790), (1416, 790)],
         label='Audit queries', tech='HTTPS/REST', at=(1290, 790)),
    dict(**{'from': 'gw', 'to': 'obs'}, fs='r', ts='l', dashed=True, label='Metrics, logs, traces\n(every container)',
         at=(1245, 640)),
    dict(**{'from': 'identity', 'to': 'redis'}, fs='t', ft=0.2, ts='b', tt=0.25, label='Revocations', tech='RESP',
         at=(148, 790)),
    dict(**{'from': 'wallet', 'to': 'redis'}, fs='t', ft=17 / 340, ts='b', tt=417 / 420,
         label='Balance\nread model', tech='RESP', at=(440, 775)),
    dict(**{'from': 'gw', 'to': 'broker'}, fs='b', ft=0.98, ts='t', tt=(1250 - 80) / 1940, via=[(1180, 760), (1250, 760)],
         label='Rejected / duplicate\nattempts', tech='AMQP', at=(1250, 1040)),
    dict(**{'from': 'notif', 'to': 'provider'}, fs='r', ts='l', label='Sends', tech='HTTPS', at=(2075, 910)),
]
for i, (sid, dbid, rel) in enumerate([('identity', 'identitydb', 'Reads / writes'), ('wallet', 'walletdb', 'ACID + outbox'),
                                       ('transfer', 'transferdb', 'State + outbox'), ('audit', 'auditdb', 'Insert + read only'),
                                       ('notif', 'notifdb', 'Reads / writes')]):
    L2_EDGES.append(dict(**{'from': sid, 'to': dbid}, fs='b', ft=DW / 2 / SW, ts='t', label=rel, tech='JDBC',
                         at=(SX[i] + DW / 2, SY + SH + 42)))
for i, (sid, label, both) in enumerate([('wallet', 'Commands in,\nevents out', True), ('transfer', 'Commands out,\nwallet.events in', True),
                                         ('audit', 'audit.events in\n(every attempt)', False), ('notif', 'transfer.events in\n(outcomes)', False)]):
    col = SX[i + 1]
    x = col + 305
    L2_EDGES.append(dict(**{'from': sid, 'to': 'broker'} if both else {'from': 'broker', 'to': sid},
                         fs='b' if both else 't', ft=((x - col) / SW) if both else ((x - 80) / 1940),
                         ts='t' if both else 'b', tt=((x - 80) / 1940) if both else ((x - col) / SW),
                         both=both, label=label, tech='AMQP', at=(x, 1285)))

open(f'{OUT}/c4-context.html', 'w').write(page('Finova P2P Virtual Wallet - System Context (C4 Level 1)', 1600, 1080, L1, L1_EDGES,
    legend=(1150, 860, 400, [('person', 'Person'), ('system', 'Finova (the system)'), ('external', 'External system')],
            'The system is decomposed into containers in the Level 2 diagram (finova-C4.png).')))
open(f'{OUT}/c4-containers.html', 'w').write(page('Finova P2P Virtual Wallet - Container Diagram (C4 Level 2)', 2520, 1560, L2, L2_EDGES,
    boundary=(40, 345, 2020, 1170, 'Finova P2P Virtual Wallet', 'Software System'),
    legend=(2110, 1050, 360, [('person', 'Person'), ('client', 'Client container'), ('container', 'Service container'),
                              ('db', 'Database (one per service)'), ('redis', 'Redis'), ('broker', 'RabbitMQ'),
                              ('obs', 'Observability'), ('external', 'External system')],
            'Every service is hexagonal and owns its database. Every message carries transferId and correlationId. '
            'Only the Gateway API (through the web app) is exposed.')))
print('ok')
