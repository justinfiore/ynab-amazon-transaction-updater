#!/usr/bin/env bash

script_dir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$script_dir" || exit 1
umask 077
if [ -f "$HOME/.amazon.conf" ]; then
  set -a
  source "$HOME/.amazon.conf"
  set +a
fi
unset AMAZON_OTP_SECRET_KEY
echo "Moving log files ..."
mv logs/out.log.4 logs/out.log.5
mv logs/out.log.3 logs/out.log.4
mv logs/out.log.2 logs/out.log.3
mv logs/out.log.1 logs/out.log.2
mv logs/out.log logs/out.log.1
echo "Launching YNABAmazonTransactionUpdater"
echo "Logs going to ${script_dir}/logs/out.log"
./build/install/ynab-amazon-transaction-updater/bin/ynab-amazon-transaction-updater &> logs/out.log
ret_code=$?
if [ "$ret_code" == "0" ]; then
  echo "YNABAmazonTransactionUpdater completed successfully."
else
  echo "YNABAmazonTransactionUpdater failed. Check the logs."
fi
exit "$ret_code"
