"""Inner-loop (sweep / iteration) path config. Derives SWEEP from this file's OWN location.
Every path the sweep OWNS is under SWEEP/. The only two outer-loop refs are INPUTS the
iteration consumes: STORE (the dig's baseline corpus) and SKILL (the artifact under test)."""
from pathlib import Path
SWEEP    = Path(__file__).resolve().parent            # current_sweep/
ATTEMPT  = SWEEP.parent                                # outer ralph loop
ROOT     = ATTEMPT.parent
DATASETS = SWEEP / "datasets"
DATASET  = DATASETS / "dataset-shas.json"             # default per-run draw
DATASET_B= DATASETS / "dataset-shas-B.json"
OUT      = SWEEP / "out"
LOGS     = SWEEP / "logs"
DRIVE    = SWEEP / "agent_drive_one.sh"
OHRUN    = SWEEP / "oh_run.py"
CFG      = SWEEP                                       # agent .json configs live here (mounted /cfg)
# --- outer-loop INPUTS (read-only) ---
STORE    = ATTEMPT / "corpus" / "baselines_peryear.json.jsonl"
SKILL    = ATTEMPT / ".agents" / "skills" / "bump-java-version"
# --- substrate ($HOME) ---
HOME     = Path.home()
M2       = HOME / ".m2-fitness"
SETTINGS = HOME / "maven-config" / "settings.xml"
