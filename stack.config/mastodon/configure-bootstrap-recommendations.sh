#!/usr/bin/env bash
set -euo pipefail

echo "Waiting for Redis availability before configuring Mastodon recommendations..."
for i in $(seq 1 30); do
  if (echo > /dev/tcp/valkey/6379) >/dev/null 2>&1; then
    echo "Redis is reachable"
    break
  fi
  if [ "$i" -eq 30 ]; then
    echo "Redis is not reachable after retries; skipping recommendation configuration."
    exit 0
  fi
  sleep 2
done

follow_fragment_dir="${MASTODON_BOOTSTRAP_FOLLOW_FRAGMENT_DIR:-/opt/mastodon/config/bootstrap-follows.d}"
recommended_accounts=()

# Follow packs contribute one handle per line. Keeping the base module empty means
# an instance opts into editorial defaults deliberately through stack modules.
if [ -d "$follow_fragment_dir" ]; then
  while IFS= read -r handle; do
    handle="${handle%%#*}"
    handle="$(printf '%s' "$handle" | xargs)"
    [ -n "$handle" ] && recommended_accounts+=("$handle")
  done < <(find "$follow_fragment_dir" -maxdepth 1 -type f -name '*.txt' -print0 | sort -z | xargs -0 -r cat)
fi

recommended_accounts_csv="$(IFS=,; echo "${recommended_accounts[*]}")"
MASTODON_BOOTSTRAP_RECOMMENDATIONS="$recommended_accounts_csv" bundle exec rails runner '
  require "timeout"

  handles = ENV.fetch("MASTODON_BOOTSTRAP_RECOMMENDATIONS", "").split(",").map(&:strip).reject(&:empty?)
  resolved = []

  handles.each do |handle|
    attempts = 0
    begin
      username, domain = handle.delete_prefix("@").split("@", 2)
      account = Account.find_by(username: username.downcase, domain: domain&.downcase)
      account ||= if domain.nil?
        nil
      else
        Timeout.timeout(ENV.fetch("MASTODON_RECOMMENDATION_RESOLVE_TIMEOUT_SECONDS", "30").to_i) do
          ResolveAccountService.new.call(handle, skip_cache: true)
        end
      end
      if account.nil?
        puts "warn recommendation: could not resolve #{handle}"
        next
      end
      if account.suspended? || account.silenced? || account.moved?
        puts "warn recommendation: skipping #{account.acct} because it is not recommendable"
        next
      end
      account.update!(discoverable: true) unless account.discoverable?
      resolved << account.acct
      puts "ok recommendation: resolved #{handle} as #{account.acct}"
    rescue Timeout::Error => e
      puts "warn recommendation: #{handle} timed out: #{e.class}"
    rescue => e
      attempts += 1
      transient = e.is_a?(ActiveRecord::ConnectionNotEstablished) ||
                  e.message.include?("database system is shutting down") ||
                  e.message.include?("Redis::CannotConnectError")
      if transient && attempts < 8
        puts "retry recommendation: #{handle} after transient error (#{e.class})"
        sleep 3
        retry
      end
      puts "warn recommendation: #{handle} failed: #{e.class} #{e.message}"
    end
  end

  unique = resolved.uniq
  Setting.bootstrap_timeline_accounts = unique.join(",")

  follow_seed_usernames = ENV.fetch("MASTODON_BOOTSTRAP_FOLLOW_USERNAMES", "").split(",").map(&:strip).reject(&:empty?)
  follow_seed_accounts = if follow_seed_usernames.empty?
    scope = User.joins(:account).merge(Account.where(domain: nil))
    scope = scope.where(approved: true) if User.column_names.include?("approved")
    scope = scope.where(disabled: [false, nil]) if User.column_names.include?("disabled")
    scope = scope.where.not(confirmed_at: nil) if User.column_names.include?("confirmed_at")
    scope.where.not(accounts: { username: "mastodon.internal" }).map(&:account)
  else
    follow_seed_usernames.filter_map do |username|
      Account.find_by(username: username.downcase, domain: nil)
    end
  end

  follow_seeded = []
  follow_seed_skipped = []

  follow_seed_accounts.uniq.each do |source_account|
    unique.each do |acct|
      username, domain = acct.split("@", 2)
      target_account = Account.find_remote(username, domain)

      if target_account.nil?
        follow_seed_skipped << "#{source_account.acct}->#{acct}:missing"
        next
      end

      if source_account.id == target_account.id
        follow_seed_skipped << "#{source_account.acct}->#{acct}:self"
        next
      end

      if source_account.following?(target_account) || source_account.requested?(target_account)
        follow_seed_skipped << "#{source_account.acct}->#{target_account.acct}:existing"
        next
      end

      begin
        FollowService.new.call(source_account, target_account, bypass_limit: true)
        follow_seeded << "#{source_account.acct}->#{target_account.acct}"
        puts "ok follow seed: #{source_account.acct} -> #{target_account.acct}"
      rescue => e
        follow_seed_skipped << "#{source_account.acct}->#{target_account.acct}:#{e.class}"
        puts "warn follow seed: #{source_account.acct} -> #{target_account.acct} failed: #{e.class} #{e.message}"
      end
    end
  end

  cache_root = Rails.root.join("public", "system", "cache").to_s
  cleaned = []
  models = ActiveRecord::Base.descendants.select do |model|
    begin
      !model.abstract_class? && model.table_exists?
    rescue
      false
    end
  end

  models.each do |model|
    model.column_names.grep(/_file_name$/).each do |column|
      attachment = column.sub(/_file_name$/, "")
      model.where.not(column => [nil, ""]).find_each do |record|
        file = record.public_send(attachment) rescue nil
        next unless file&.respond_to?(:path)

        path = begin
          file.path(:original)
        rescue
          begin
            file.path
          rescue
            nil
          end
        end

        next unless path.present? && path.start_with?(cache_root) && !File.exist?(path)

        file.clear
        record.save!(validate: false)
        cleaned << "#{model.name}(#{record.id}).#{attachment}"
      rescue => e
        puts "warn recommendation: failed cleaning #{model.name}(#{record.id}).#{attachment}: #{e.class} #{e.message}"
      end
    end
  end

  begin
    Rails.cache.delete_matched("follow_recommendations/*")
  rescue => e
    puts "warn recommendation: cache invalidation skipped: #{e.class} #{e.message}"
  end

  puts "configured bootstrap_timeline_accounts=#{Setting.bootstrap_timeline_accounts}"
  puts "follow seeded relationships=#{follow_seeded.size}"
  puts "follow seed skipped relationships=#{follow_seed_skipped.size}"
  puts "cleaned missing cached recommendation attachments=#{cleaned.size}"
'
