#!/usr/bin/env bash

script_dir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd $script_dir
echo "Moving log files ..."
mv logs/out.log.4 logs/out.log.5
mv logs/out.log.3 logs/out.log.4
mv logs/out.log.2 logs/out.log.3
mv logs/out.log.1 logs/out.log.2
mv logs/out.log logs/out.log.1
echo "Launching YNABAmazonTransactionUpdater"
echo "Logs going to ${script_dir}/logs/out.log"
./build/install/YNABAmazonTransactionUpdater/bin/YNABAmazonTransactionUpdater &> logs/out.log
