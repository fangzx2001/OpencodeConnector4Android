#!/bin/bash
# ================================================
#  OConnector Server Startup Script (Linux/macOS)
#  Starts OpenCode in server mode for remote access
# ================================================

HOST="0.0.0.0"
PORT="4096"

echo ""
echo "  ╔═══════════════════════════════════════════╗"
echo "  ║     OConnector Server Launcher           ║"
echo "  ╚═══════════════════════════════════════════╝"
echo ""

echo "  [*] Starting OpenCode server..."
echo "  [*] Host: $HOST"
echo "  [*] Port: $PORT"
echo ""

# Get local IP
LOCAL_IP=$(hostname -I 2>/dev/null | awk '{print $1}')
if [ -n "$LOCAL_IP" ]; then
    echo "  [*] Your PC local IP: $LOCAL_IP"
    echo "  [*] Connect from phone: http://$LOCAL_IP:$PORT"
    echo ""
fi

# Start OpenCode server
opencode serve --hostname="$HOST" --port="$PORT"
