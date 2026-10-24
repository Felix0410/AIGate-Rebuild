FROM mysql:8.4@sha256:6ea90827b1100f8f2ae306a539f86d2c264a26ed435a2a9f75551dd5c3aeb242
COPY --chmod=0644 infra/10-aigate-users.sh /docker-entrypoint-initdb.d/10-aigate-users.sh
