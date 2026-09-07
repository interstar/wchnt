(ns neo4j.explorer
  (:require [clojure.string :as str]))

(defn explorer-imports []
  (str "# Generated from WCHNT schema\n"
       "import atexit\n"
       "import html\n"
       "from pathlib import Path\n"
       "from urllib.parse import quote\n\n"
       "from bottle import Bottle, response, run, request, static_file\n"
       "from neo4j import GraphDatabase\n\n"))

(defn explorer-driver-setup []
  (str "# Neo4j credentials\n"
       "URI = \"bolt://localhost:7687\"\n"
       "AUTH = (\"neo4j\", \"testpass\")\n\n"
       "driver = GraphDatabase.driver(URI, auth=AUTH)\n"
       "app = Bottle()\n"
       "atexit.register(driver.close)\n"
       "STATIC_ROOT = Path(__file__).resolve().parent / \"static\"\n"
       "NODE_ROOT = STATIC_ROOT / \"vendor\" / \"esm\" / \"node\"\n\n"))

(defn explorer-helpers []
  (str "def link_to_node(label, uid, text=None):\n"
       "    if uid is None or label is None:\n"
       "        return \"\"\n"
       "    title = text if text is not None else f\"{label} {uid}\"\n"
       "    return f\"<a href='/node/{quote(label)}/{quote(str(uid))}'>\" + html.escape(str(title)) + \"</a>\"\n\n"
       "def render_page(title, body):\n"
       "    return (\"<!doctype html><html><head><meta charset='utf-8'>\"\n"
       "            f\"<title>{html.escape(title)}</title>\"\n"
       "            \"<style>body{font-family:system-ui, sans-serif;margin:24px;}\"\n"
       "            \"table{border-collapse:collapse;width:100%;}\"\n"
       "            \"th,td{border:1px solid #ccc;padding:6px 8px;text-align:left;}\"\n"
       "            \"a{text-decoration:none;color:#1a5;}\"\n"
       "            \"</style></head><body>\"\n"
       "            f\"<h1>{html.escape(title)}</h1>\" + body + \"</body></html>\")\n\n"
       "def parse_uid(value):\n"
       "    try:\n"
       "        return int(value)\n"
       "    except (TypeError, ValueError):\n"
       "        return value\n\n"))

(defn python-explorer-header []
  (str (explorer-imports)
       (explorer-driver-setup)
       (explorer-helpers)))

(defn class-schema-entry [class-name schema-map]
  (let [meta (get schema-map class-name)
        properties (:properties meta)
        relations (:relations meta)]
    (str "    " (pr-str class-name) ": {\n"
         "        \"properties\": [" (str/join ", " (map pr-str properties)) "],\n"
         "        \"relations\": [" (str/join ", " relations) "],\n"
         "    },\n")))

(defn python-schema [class-list schema-map]
  (str "SCHEMA = {\n"
       (str/join "" (map #(class-schema-entry % schema-map) class-list))
       "}\n\n"
       "CLASS_LIST = [" (str/join ", " (map pr-str class-list)) "]\n\n"))

(defn index-route []
  (str "@app.route('/')\n"
       "def index():\n"
       "    items = ''.join([f\"<li><a href='/class/{quote(name)}'>\" + html.escape(name) + \"</a></li>\" for name in CLASS_LIST])\n"
       "    return render_page('Classes', f\"<ul>{items}</ul>\")\n\n"))

(defn class-list-query-helper []
  (str "def build_class_list_query(label, props, rels):\n"
       "    cols = ['uid'] + props + [rel['name'] for rel in rels]\n"
       "    count_query = f\"MATCH (n:{label}) RETURN count(n) AS total\"\n"
       "    match_lines = [f\"MATCH (n:{label})\"]\n"
       "    with_vars = ['n']\n"
       "    rel_vars = []\n"
       "    for idx, rel in enumerate(rels):\n"
       "        rvar = f\"r{idx}\"\n"
       "        mvar = f\"m{idx}\"\n"
       "        relvar = f\"rel{idx}\"\n"
       "        rel_vars.append(relvar)\n"
       "        match_lines.append(f\"OPTIONAL MATCH (n)-[{rvar}:{rel['rel_type']}]->({mvar}:{rel['target']})\")\n"
       "        match_lines.append(f\"WITH {', '.join(with_vars)}, collect({{uid: {mvar}.uid, hint: {rvar}.hint}}) AS {relvar}\")\n"
       "        with_vars.append(relvar)\n"
       "    select_cols = [f\"n.{c} AS {c}\" for c in ['uid'] + props]\n"
       "    for rel, relvar in zip(rels, rel_vars):\n"
       "        select_cols.append(f\"{relvar}[0].uid AS {rel['name']}_uid\")\n"
       "        select_cols.append(f\"{relvar}[0].hint AS {rel['name']}_hint\")\n"
       "    match_lines.append(f\"RETURN {', '.join(select_cols)} ORDER BY n.uid SKIP $skip LIMIT $limit\")\n"
       "    return cols, count_query, \"\\n\".join(match_lines)\n\n"))

(defn class-list-table-helper []
  (str "def render_class_table(label, props, rels, cols, rows):\n"
       "    header = ''.join([f\"<th>{html.escape(c)}</th>\" for c in cols])\n"
       "    body_rows = []\n"
       "    for row in rows:\n"
       "        cells = []\n"
       "        uid = row.get('uid')\n"
       "        cells.append(f\"<td>{link_to_node(label, uid, uid)}</td>\")\n"
       "        for c in props:\n"
       "            cells.append(f\"<td>{html.escape(str(row.get(c)))}</td>\")\n"
       "        for rel in rels:\n"
       "            rel_uid = row.get(f\"{rel['name']}_uid\")\n"
       "            rel_hint = row.get(f\"{rel['name']}_hint\")\n"
       "            if rel_uid is None:\n"
       "                cells.append(\"<td></td>\")\n"
       "            else:\n"
       "                hint_text = f\"{rel_hint} ({rel_uid})\" if rel_hint else rel_uid\n"
       "                cells.append(f\"<td>{link_to_node(rel['target'], rel_uid, hint_text)}</td>\")\n"
       "        body_rows.append('<tr>' + ''.join(cells) + '</tr>')\n"
       "    return f\"<table><thead><tr>{header}</tr></thead><tbody>{''.join(body_rows)}</tbody></table>\"\n\n"))

(defn class-list-controls-helper []
  (str "def render_class_controls(label, page, size, last_page):\n"
       "    nav_parts = []\n"
       "    if page > 1:\n"
       "        nav_parts.append(f\"<a href='/class/{quote(label)}?page={page-1}&size={size}'>Prev</a>\")\n"
       "    if page < last_page:\n"
       "        nav_parts.append(f\"<a href='/class/{quote(label)}?page={page+1}&size={size}'>Next</a>\")\n"
       "    nav = \" | \".join(nav_parts) if nav_parts else \"\"\n"
       "    slider = (\n"
       "        f\"<label>Page size: <input type='range' min='1' max='500' value='{size}' \"\n"
       "        f\"oninput=\\\"document.getElementById('sizeVal').textContent=this.value\\\" \"\n"
       "        f\"onchange=\\\"window.location='/class/{quote(label)}?page=1&size=' + this.value\\\"\\\"></label> \"\n"
       "        f\"<span id='sizeVal'>{size}</span>\"\n"
       "    )\n"
       "    pager = f\"<p>Page {page} of {last_page}. {nav}</p>\"\n"
       "    delete_form = (\n"
       "        f\"<form method='post' action='/class/{quote(label)}/delete-all' \"\n"
       "        f\"onsubmit=\\\"return confirm('Delete all {label} nodes?')\\\">\"\n"
       "        f\"<button type='submit'>Delete all {html.escape(label)}</button></form>\"\n"
       "    )\n"
       "    return f\"<div>{delete_form}<div style='margin-top:12px;'>{slider}</div>{pager}</div>\"\n\n"))

(defn class-list-route []
  (str "@app.route('/class/<label>')\n"
       "def class_list(label):\n"
       "    meta = SCHEMA.get(label)\n"
       "    if meta is None:\n"
       "        response.status = 404\n"
       "        return render_page('Not Found', 'Unknown class')\n"
       "    page = int(request.query.get('page', '1') or 1)\n"
       "    size = int(request.query.get('size', '50') or 50)\n"
       "    if page < 1:\n"
       "        page = 1\n"
       "    if size < 1:\n"
       "        size = 1\n"
       "    if size > 500:\n"
       "        size = 500\n"
       "    skip = (page - 1) * size\n"
       "    props = meta['properties']\n"
       "    rels = meta['relations']\n"
       "    cols, count_query, query = build_class_list_query(label, props, rels)\n"
       "    with driver.session() as session:\n"
       "        total = session.run(count_query).single().get('total')\n"
       "        rows = [dict(record) for record in session.run(query, skip=skip, limit=size)]\n"
       "    last_page = max(1, (total + size - 1) // size)\n"
       "    if page > last_page:\n"
       "        page = last_page\n"
       "    table = render_class_table(label, props, rels, cols, rows)\n"
       "    controls = render_class_controls(label, page, size, last_page)\n"
       "    return render_page(f\"{label} List\", controls + table)\n\n"))

(defn delete-class-route []
  (str "@app.post('/class/<label>/delete-all')\n"
       "def delete_class(label):\n"
       "    if label not in SCHEMA:\n"
       "        response.status = 404\n"
       "        return render_page('Not Found', 'Unknown class')\n"
       "    query = f\"MATCH (n:{label}) DETACH DELETE n\"\n"
       "    with driver.session() as session:\n"
       "        session.run(query)\n"
       "    response.status = 303\n"
       "    response.set_header('Location', f\"/class/{quote(label)}\")\n"
       "    return ''\n\n"))

(defn node-detail-route []
  (str "@app.route('/node/<label>/<uid>')\n"
       "def node_detail(label, uid):\n"
       "    uid_value = parse_uid(uid)\n"
       "    query = (\"MATCH (n:\" + label + \" {uid: $uid}) \"\n"
       "             \"OPTIONAL MATCH (n)-[r]->(o) \"\n"
       "             \"WITH n, collect({dir:'out', type:type(r), label:head(labels(o)), uid:o.uid}) AS outs \"\n"
       "             \"OPTIONAL MATCH (i)-[r2]->(n) \"\n"
       "             \"WITH n, outs, collect({dir:'in', type:type(r2), label:head(labels(i)), uid:i.uid}) AS ins \"\n"
       "             \"RETURN n, outs, ins\")\n"
       "    with driver.session() as session:\n"
       "        record = session.run(query, uid=uid_value).single()\n"
       "    if record is None or record.get('n') is None:\n"
       "        response.status = 404\n"
       "        return render_page('Not Found', 'Node not found')\n"
       "    node = record.get('n')\n"
       "    props = dict(node)\n"
       "    prop_rows = ''.join([f\"<tr><th>{html.escape(str(k))}</th><td>{html.escape(str(v))}</td></tr>\" for k, v in props.items()])\n"
       "    prop_table = f\"<table><tbody>{prop_rows}</tbody></table>\"\n"
       "    links = []\n"
       "    for item in record.get('outs') or []:\n"
       "        if item.get('uid') is not None and item.get('label') is not None:\n"
       "            links.append(f\"<li>out {html.escape(item.get('type'))}: \" + link_to_node(item.get('label'), item.get('uid')) + \"</li>\")\n"
       "    for item in record.get('ins') or []:\n"
       "        if item.get('uid') is not None and item.get('label') is not None:\n"
       "            links.append(f\"<li>in {html.escape(item.get('type'))}: \" + link_to_node(item.get('label'), item.get('uid')) + \"</li>\")\n"
       "    rels = f\"<ul>{''.join(links)}</ul>\" if links else \"<p>No relations.</p>\"\n"
       "    nav = (f\"<p><a href='/'>Home</a> | <a href='/class/{quote(label)}'>Back to {html.escape(label)}</a></p>\")\n"
       "    body = nav + prop_table + \"<h2>Relations</h2>\" + rels\n"
       "    return render_page(f\"{label} {uid}\", body)\n\n"))

(defn static-route []
  (str "@app.route('/static/<filepath:path>')\n"
       "def server_static(filepath):\n"
       "    return static_file(filepath, root=STATIC_ROOT)\n\n"))

(defn app-main []
  (str "if __name__ == '__main__':\n"
       "    run(app, host='127.0.0.1', port=8080, debug=True)\n"))

(defn python-webserver [class-list schema-map]
  (str (python-explorer-header)
       (python-schema class-list schema-map)
       (index-route)
       (class-list-query-helper)
       (class-list-table-helper)
       (class-list-controls-helper)
       (class-list-route)
       (delete-class-route)
       (node-detail-route)
       (static-route)
       (app-main)))
