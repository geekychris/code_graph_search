#!/usr/bin/env bash
# Builds the image on spark, imports it into microk8s' containerd, applies
# deploy/k8s/codegraph.yaml (image pinned to the commit, NodePort 30930) and
# rolls the Deployment. Re-run to redeploy.
#
#   scripts/deploy-spark.sh
#   CODEGRAPH_HOST=192.168.1.177 scripts/deploy-spark.sh
#
# CODEGRAPH_HOST  ssh host (default spark.local)
# SSH_OPTS        extra ssh/rsync options
# NODE_PORT       NodePort for the API, UI and MCP (default 30930)
# KUBECTL         kubectl on the host (default "microk8s kubectl")
#
# The codegraph-secrets Secret must exist; see the header of
# deploy/k8s/codegraph.yaml. Read a token on the host with:
#   microk8s kubectl -n codegraph get secret codegraph-secrets -o jsonpath='{.data.CODEGRAPH_ADMIN_TOKEN}' | base64 -d
set -euo pipefail
cd "$(dirname "$0")/.."

host="${CODEGRAPH_HOST:-spark.local}"
ssh_opts=(${SSH_OPTS:-})
kubectl="${KUBECTL:-microk8s kubectl}"
node_port="${NODE_PORT:-30930}"
remote_dir="build/code_graph_search"
image="codegraph:$(git describe --always --dirty 2>/dev/null || echo dev)"
on() { ssh "${ssh_opts[@]}" "$host" "$@"; }
say() { printf '\n[deploy-codegraph] %s\n' "$*"; }

say "staging sources on $host:~/$remote_dir"
on "mkdir -p ~/$remote_dir"
rsync -az --delete -e "ssh ${ssh_opts[*]}" \
  --exclude .git --exclude '*/target' --exclude 'ui/frontend/node_modules' --exclude 'ui/frontend/dist' \
  ./ "$host:$remote_dir/"

say "building $image on $host"
on "cd ~/$remote_dir && docker build -q -t $(printf %q "$image") ."

say "importing into microk8s containerd"
on "docker save $(printf %q "$image") | ctr -a /var/snap/microk8s/common/run/containerd.sock -n k8s.io images import -"

on "$kubectl -n codegraph get secret codegraph-secrets >/dev/null 2>&1" || {
  echo "codegraph-secrets is missing in namespace codegraph; create it first (see deploy/k8s/codegraph.yaml)" >&2
  exit 1
}

say "applying manifests"
# perl rather than sed: BSD sed (macOS) has no \n in replacements
perl -pe "s|image: ghcr.io/geekychris/code-graph-search:latest|image: $image\n          imagePullPolicy: Never|;
          s|      targetPort: http|      targetPort: http\n      nodePort: $node_port|" \
    deploy/k8s/codegraph.yaml | on "$kubectl apply -f -"
on "$kubectl -n codegraph rollout status deployment/codegraph --timeout=240s"

say "done: http://${host}:${node_port}/api/health  (MCP at /mcp)"
