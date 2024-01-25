import subprocess
import time
import threading

import paramiko
import psutil

INIT = "INIT"
process_pid = {}

# Reference: https://stackoverflow.com/questions/287871/how-do-i-print-colored-text-to-the-terminal
class Bcolors:
    HEADER = '\033[95m'
    OKBLUE = '\033[94m'
    OKGREEN = '\033[92m'
    WARNING = '\033[93m'
    FAIL = '\033[91m'
    ENDC = '\033[0m'

    def disable(self):
        self.HEADER = ''
        self.OKBLUE = ''
        self.OKGREEN = ''
        self.WARNING = ''
        self.FAIL = ''
        self.ENDC = ''


def load_hosts(hosts_path):
    with open(hosts_path, 'r') as hostsFile:
        hosts = [line.rstrip() for line in hostsFile]

    print(Bcolors.OKGREEN + "[OK] Hosts file loaded: " + Bcolors.ENDC)
    print(hosts)

    return hosts


def remove_host(hosts, hosts_offline, hosts_path):
    if len(hosts_offline) != 0:

        print(Bcolors.WARNING + "[WARNING] Hosts offline:" + Bcolors.ENDC)
        print(hosts_offline)

        for hostName in hosts_offline:
            hosts.remove(hostName)

        with open(hosts_path, 'w') as hostsFile:
            hostsFile.write('\n'.join(hosts) + '\n')


def compile_simpleproxy():
    try:
        subprocess.run(["javac", "SimpleProxyServer.java"], check=True)
        print(Bcolors.OKGREEN + "[OK] Reverse Proxy Server compiled." + Bcolors.ENDC)
    except Exception as e:
        print(Bcolors.FAIL + "[FAIL] Unexpected error when compiling Reverse Proxy Server: " + str(e) + Bcolors.ENDC)
        exit(1)


def compile_urlshortener():
    try:
        subprocess.run(["javac", "URLShortener.java"], check=True)
        print(Bcolors.OKGREEN + "[OK] URL Shortener compiled." + Bcolors.ENDC)
    except Exception as e:
        print(Bcolors.FAIL + "[FAIL] Unexpected error when compiling URL Shortener: " + str(e) + Bcolors.ENDC)
        exit(1)


def create_db(hostName, mode=None):
    failed = False
    try:
        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        ssh.connect(hostName)

        if mode == INIT:
            create_db = f"cp ./csc409/a1group66/a1/database.sqlite /virtual/{hostName}.sqlite"
        else:
            create_db = f"sqlite3 /virtual/{hostName}.sqlite 'CREATE TABLE IF NOT EXISTS URL (shortURL TEXT PRIMARY KEY, longURL TEXT);'"
        ssh.exec_command(create_db)
    except paramiko.ssh_exception.NoValidConnectionserror as e:
        print(Bcolors.FAIL + f"[FAIL] SSH connection error to {hostName}: " + str(e) + Bcolors.ENDC)
        failed = True
    except Exception as e:
        print(Bcolors.FAIL + f"[FAIL] Unexpected error on {hostName}: " + str(e) + Bcolors.ENDC)
        failed = True
    finally:
        ssh.close()

    if failed:
        print(Bcolors.FAIL + f"[FAIL] Error when installing {hostName}.sqlite on host {hostName}." + Bcolors.ENDC)
        return hostName
    print(Bcolors.OKGREEN + f"[OK] {hostName}.sqlite has installed on host {hostName}." + Bcolors.ENDC)
    return None


def launch_host(hostName):
    failed = False
    try:
        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        ssh.connect(hostName)

        launch_url_shortener = f"cd ./csc409/a1group66/a1/; java -classpath '.:sqlite-jdbc-3.43.0.0.jar' URLShortener {hostName}"
        ssh.exec_command(launch_url_shortener)

    except Exception as e:
        print(Bcolors.FAIL + f"[FAIL] Unexpected error on {hostName}: " + str(e) + Bcolors.ENDC)
        failed = True
    finally:
        ssh.close()

    if failed:
        print(Bcolors.FAIL + f"[FAIL] Error when launching URL Shortener on host {hostName}." + Bcolors.ENDC)
        return hostName
    print(Bcolors.OKGREEN + f"[OK] URL Shortener is launched on host {hostName}." + Bcolors.ENDC)
    return None


def launch_proxy():
    try:
        proxy_process = subprocess.Popen("java -classpath '.:sqlite-jdbc-3.43.0.0.jar' SimpleProxyServer", shell=True)
        print(Bcolors.OKGREEN + "[OK] Reverse proxy server launched." + Bcolors.ENDC)

    except Exception as e:
        print(Bcolors.FAIL + "[FAIL] Unexpected error when launching reverse proxy server: " + str(e) + Bcolors.ENDC)
        exit(1)

    print(psutil.Process(proxy_process.pid).children())

    return psutil.Process(proxy_process.pid).children()[0].pid


def monitor_url_shortener(hostName):
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(hostName)

    _, stdout, _ = ssh.exec_command("pgrep -f URLShortener")
    pid = stdout.read().decode().strip()

    if not pid:
        ssh.close()
        print(Bcolors.FAIL + f"[FAIL] URL Shortener is offline on host {hostName} with pid {pid}." + Bcolors.ENDC)
        return hostName

    process_pid[hostName] = pid
    print(Bcolors.OKGREEN + f"[OK] URL Shortener is online on host {hostName} with pid {pid}." + Bcolors.ENDC)
    ssh.close()
    return None


def kill_all_process():
    print(Bcolors.WARNING + "[WARNING] Shutting down system." + Bcolors.ENDC)

    for hostName in process_pid:
        pid = process_pid.get(hostName, None)
        if pid is None:
            pass
        if hostName == "localhost":
            subprocess.Popen(f"kill {pid}", shell = True)
        else:
            ssh = paramiko.SSHClient()
            ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
            ssh.connect(hostName)

            _, stdout, _ = ssh.exec_command(f"kill {pid}")

    print(Bcolors.OKBLUE + "[OK] System offline." + Bcolors.ENDC)
    exit(1)


def start_monitoring(hosts, pid):
    print(Bcolors.OKBLUE + "[OK] Monitoring System Online." + Bcolors.ENDC)

    hosts_offline = []

    while True:
        if not hosts:
            kill_all_process()

        old_hosts_set = set(hosts)

        try:
            psutil.Process(pid).is_running()
            process_pid["localhost"] = pid
            print(Bcolors.OKGREEN + f"[OK] Reverse proxy server is online on localhost with pid {pid}." + Bcolors.ENDC)

            for hostName in hosts:
                if (monitor_url_shortener(hostName)) is not None:
                    print(Bcolors.WARNING + f"[WARNING] Trying to relaunch the URL Shortener on host {hostName}." + Bcolors.ENDC)
                    if (offlineHostName := launch_host(hostName)) is not None:
                            hosts_offline.append(offlineHostName)

            if hosts_offline:
                remove_host(hosts, hosts_offline, hosts_path)

        except psutil.NoSuchProcess:
            print(Bcolors.FAIL + "[FAIL] Reverse Proxy Server is offline." + Bcolors.ENDC)
            print(Bcolors.WARNING + "[WARNING] Trying to relaunch the reverse proxy server." + Bcolors.ENDC)

            # Relaunch proxy, launch_proxy() will call exit if failed
            pid = launch_proxy()
            print(Bcolors.OKGREEN + "[OK] Reverse proxy server relaunched." + Bcolors.ENDC)

        hosts = load_hosts(hosts_path)

        if not hosts:
            kill_all_process()

        new_hosts_set = set(hosts)
        hosts_offline_set = set(hosts_offline)

        added_host = new_hosts_set - old_hosts_set - hosts_offline_set
        for host in added_host:
            print(Bcolors.OKBLUE + f"[OK] New host detected. Initializing host {host}." + Bcolors.ENDC)
            create_db(host)
            launch_host(host)

        hosts_removed = old_hosts_set - new_hosts_set - hosts_offline_set

        def shutdown_host(hosts_removed):
            time.sleep(10)
            for hostName in hosts_removed:
                ssh = paramiko.SSHClient()
                ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
                ssh.connect(hostName)
                _, stdout, _ = ssh.exec_command(f"kill {process_pid[hostName]}")
                print(Bcolors.WARNING + f"[WARNING] Hosts {hostName} has been removed with process {process_pid[hostName]}." + Bcolors.ENDC)

        t1 = threading.Thread(target=shutdown_host, args=(hosts_removed,))
        t1.start()

        hosts_offline.clear()
        time.sleep(5)


def initialize_first_shortener(hosts_list):
    init_shortener_launched = False
    index = 0
    failed_hosts = []

    while not init_shortener_launched and index < len(hosts_list):
        host = hosts_list[index]
        offline_host_name = create_db(host, INIT)
        if offline_host_name is not None:
            failed_hosts.append(offline_host_name)
        else:
            time.sleep(3)
            offline_host_name = launch_host(host)
            if offline_host_name is None:
                init_shortener_launched = True
            else:
                failed_hosts.append(offline_host_name)
        index += 1

    return host, failed_hosts


def launch_other_shorteners(other_hosts):
    failed_hosts = []
    for host in other_hosts:
        if create_db(host) is None and launch_host(host) is not None:
            failed_hosts.append(host)
    return failed_hosts


if __name__ == '__main__':
    hosts_path = './hosts.conf'
    hosts_lst = load_hosts(hosts_path)

    compile_simpleproxy()
    compile_urlshortener()

    print(Bcolors.OKBLUE + "[OK] Start Initialization (Estimated Time: 40s)." + Bcolors.ENDC)

    # Launch the fist URLShortener
    first_host, hosts_failed = initialize_first_shortener(hosts_lst)

    time.sleep(5)

    # Overwrite hosts.conf such that it only contains one host of URLShortener
    with open(hosts_path, 'w') as hostsFile:
        hostsFile.write(f"{first_host}\n")

    # Launch the Proxy server
    process = launch_proxy()
    time.sleep(15)

    # Launch the rest of the URLShortener
    other_hosts_failed = launch_other_shorteners(hosts_lst[1:])
    hosts_failed.extend(other_hosts_failed)

    final_hosts_list = [host for host in hosts_lst if host not in hosts_failed]

    if hosts_failed:
        print(Bcolors.WARNING + "[WARNING] Failed connections to remote PCs have been eliminated from hosts.conf:" + Bcolors.ENDC)
        print(hosts_failed)

    # Add hosts back to host.conf such that Proxy will see hosts.conf changed and start data migration
    # => now we have corresponding data on each URLShortener
    with open(hosts_path, 'w') as hostsFile:
        hostsFile.write('\n'.join(final_hosts_list) + '\n')

    time.sleep(15)

    print(Bcolors.OKBLUE + "[OK] Initialization Finished" + Bcolors.ENDC)
    start_monitoring(final_hosts_list[:], process)
