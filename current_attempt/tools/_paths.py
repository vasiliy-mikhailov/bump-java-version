"""Single source of truth for dig + loop-B PYTHON paths. Derives the attempt root from this
file's OWN location -- never a hardcoded /home or attempt_N (AGENTS.md __file__ rule).
Tools in tools/ `import _paths` directly; tools in portability/ insert ../tools onto sys.path first."""
from pathlib import Path
ATTEMPT     = Path(__file__).resolve().parents[1]      # .../current_attempt
ROOT        = ATTEMPT.parent
CORPUS      = ATTEMPT / "corpus"
STORE       = CORPUS / "baselines_peryear.json.jsonl"
STORE_BASE  = CORPUS / "baselines_peryear.json"
CANDIDATES  = CORPUS / "discovered" / "all_candidates.txt"
REMAINING   = CORPUS / "dig_remaining.txt"
DATASETS    = CORPUS / "datasets"
DATASET     = ATTEMPT / "dataset-shas.json"            # default per-run draw
SWEEP_OUT   = ATTEMPT / "sweep_out"
LOGS        = ATTEMPT / "logs"
P12_FEED    = ATTEMPT / "p12" / "feed"
PORTABILITY = ATTEMPT / "portability"
SKILL       = ATTEMPT / ".agents" / "skills" / "bump-java-version"
DRIVE       = PORTABILITY / "agent_drive_one.sh"
OHRUN       = PORTABILITY / "oh_run.py"
HOME        = Path.home()
M2          = HOME / ".m2-fitness"                     # substrate (stays at $HOME by contract)
SETTINGS    = HOME / "maven-config" / "settings.xml"   # substrate
