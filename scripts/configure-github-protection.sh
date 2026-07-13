#!/usr/bin/env bash
set -euo pipefail

REPO="${BUBBLE_SKY_REPO:-anderote/bubble-sky}"
BRANCH="${BUBBLE_SKY_BRANCH:-main}"

gh api --method PATCH "repos/$REPO" -f allow_auto_merge=true >/dev/null
gh api --method PUT "repos/$REPO/branches/$BRANCH/protection" --input - <<'JSON'
{
  "required_status_checks": {
    "strict": true,
    "contexts": [
      "control-plane",
      "mods (bubble-sky-mod)",
      "mods (towerdefense)"
    ]
  },
  "enforce_admins": true,
  "required_pull_request_reviews": {
    "dismiss_stale_reviews": true,
    "require_code_owner_reviews": false,
    "required_approving_review_count": 1,
    "require_last_push_approval": true
  },
  "restrictions": null,
  "required_conversation_resolution": true,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "block_creations": false,
  "required_linear_history": true,
  "lock_branch": false,
  "allow_fork_syncing": true
}
JSON

echo "Protected $REPO:$BRANCH with CI + one partner approval; auto-merge enabled."
