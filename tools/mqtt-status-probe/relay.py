"""Temporary device-only TCP relay using the existing Python firewall permission.

Does not change firewall/device settings. The MQTTnet receiver on port 1885
validates status topics and rejects all device command subscriptions.
"""
import json
import os
from pathlib import Path
import select
import socket
import threading
import time

settings = json.loads((Path(os.environ['LOCALAPPDATA']) / 'jetkvm-monitor-switcher/settings.json').read_text(encoding='utf-8-sig'))
bind = settings['ReceiverIp']
device = settings['JetIp']
deadline = time.monotonic() + 55

def relay(incoming):
    try:
        with incoming, socket.create_connection((bind, 1885), timeout=3) as backend:
            incoming.settimeout(3)
            while time.monotonic() < deadline:
                ready, _, _ = select.select([incoming, backend], [], [], 1)
                for source in ready:
                    data = source.recv(65536)
                    if not data:
                        return
                    (backend if source is incoming else incoming).sendall(data)
    except OSError:
        pass

with socket.socket() as listener:
    listener.bind((bind, settings['ReceiverPort']))
    listener.listen(4)
    listener.settimeout(1)
    print('Temporary relay ready; accepts only configured JetKVM source.', flush=True)
    threads = []
    while time.monotonic() < deadline:
        try:
            connection, address = listener.accept()
        except socket.timeout:
            continue
        if address[0] != device:
            connection.close()
            continue
        print('Configured JetKVM TCP connection received.', flush=True)
        thread = threading.Thread(target=relay, args=(connection,), daemon=True)
        thread.start()
        threads.append(thread)
    for thread in threads:
        thread.join(timeout=2)
print('Temporary relay stopped.', flush=True)
