"""Run the command lines of a job file with N parallel workers. usage: run_jobs.py <jobs.txt> [workers=3]"""
import subprocess, sys
from concurrent.futures import ThreadPoolExecutor

jobs = [l.split() for l in open(sys.argv[1]) if l.strip()]
n = int(sys.argv[2]) if len(sys.argv) > 2 else 3


def run(c):
    r = subprocess.run(c, capture_output=True, text=True)
    print(r.stdout.strip() or r.stderr.strip()[-200:], flush=True)


with ThreadPoolExecutor(n) as ex:
    list(ex.map(run, jobs))
print("ALL DONE")
