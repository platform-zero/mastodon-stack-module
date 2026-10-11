#!/usr/bin/env bash
set -euo pipefail
# Read-only native readiness: selected recommendations and eligible local follows.
bundle exec rails runner - <<'RUBY'
begin
  root = ENV.fetch("MASTODON_BOOTSTRAP_FOLLOW_FRAGMENT_DIR", "/opt/mastodon/config/bootstrap-follows.d")
  handles = Dir.glob(File.join(root, "*.txt")).sort.flat_map { |p| File.readlines(p) }
    .map { |line| line.split("#", 2).first.to_s.strip }.reject(&:empty?).uniq
  targets = handles.map do |handle|
    username, domain = handle.delete_prefix("@").split("@", 2)
    Account.find_by(username: username.downcase, domain: domain&.downcase)
  end
  exit 1 if targets.any?(&:nil?)
  configured = Setting.bootstrap_timeline_accounts.to_s.split(",").reject(&:empty?).sort.uniq
  exit 1 unless configured == targets.map(&:acct).sort.uniq
  names = ENV.fetch("MASTODON_BOOTSTRAP_FOLLOW_USERNAMES", "").split(",").map(&:strip).reject(&:empty?)
  users = if names.empty?
    scope = User.joins(:account).merge(Account.where(domain: nil))
    scope = scope.where(approved: true) if User.column_names.include?("approved")
    scope = scope.where(disabled: [false, nil]) if User.column_names.include?("disabled")
    scope = scope.where.not(confirmed_at: nil) if User.column_names.include?("confirmed_at")
    scope.where.not(accounts: { username: "mastodon.internal" }).map(&:account)
  else
    names.map { |name| Account.find_by(username: name.downcase, domain: nil) }
  end
  exit 1 if users.any?(&:nil?)
  ready = users.all? { |source| targets.all? { |target| source.id == target.id || source.following?(target) || source.requested?(target) } }
  exit(ready ? 0 : 1)
rescue StandardError
  # Never print private database errors or configuration.
  exit 1
end
RUBY
