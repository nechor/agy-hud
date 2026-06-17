import socket
import concurrent.futures

ip = "192.168.0.68"
ports = range(30000, 65536)

def scan_port(port):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(0.1)
        if s.connect_ex((ip, port)) == 0:
            print(f"Port {port} is OPEN")

with concurrent.futures.ThreadPoolExecutor(max_workers=100) as executor:
    executor.map(scan_port, ports)
