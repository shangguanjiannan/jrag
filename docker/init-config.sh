#!/usr/bin/env sh
set -e

BASE_DIR="/config"
MILVUS_DIR="${BASE_DIR}/milvus"
VOLUME_DIR="${MILVUS_DIR}/volumes/milvus"
EMBED_ETCD_FILE="${MILVUS_DIR}/embedEtcd.yaml"
USER_FILE="${MILVUS_DIR}/user.yaml"
JRAG_CONFIG_DIR="${BASE_DIR}/config"

mkdir -p "${VOLUME_DIR}"
mkdir -p "${MILVUS_DIR}"
mkdir -p "${JRAG_CONFIG_DIR}"

if [ -d "${EMBED_ETCD_FILE}" ]; then
    rm -rf "${EMBED_ETCD_FILE}"
fi

if [ -d "${USER_FILE}" ]; then
    rm -rf "${USER_FILE}"
fi

if [ ! -f "${EMBED_ETCD_FILE}" ]; then
    cat << EOF > "${EMBED_ETCD_FILE}"
listen-client-urls: http://0.0.0.0:2379
advertise-client-urls: http://0.0.0.0:2379
quota-backend-bytes: 4294967296
auto-compaction-mode: revision
auto-compaction-retention: '1000'
EOF
fi

if [ ! -f "${USER_FILE}" ]; then
    cat << EOF > "${USER_FILE}"
# Extra config to override default milvus.yaml
EOF
fi

for file in /templates/application*.yaml; do
    if [ -f "${file}" ]; then
        base_name="$(basename "${file}")"
        target_file="${JRAG_CONFIG_DIR}/${base_name}"
        if [ ! -f "${target_file}" ]; then
            cp "${file}" "${target_file}"
        fi
    fi
done
