# Remove container if it exists (silently)
docker rm -f mariadb 2>$null

# Start MariaDB and run all SQL scripts in the database folder
docker run `
  -p 3306:3306 `
  --name mariadb `
  -e MYSQL_ROOT_PASSWORD=mysql `
  -d `
  -v "${PWD}\database:/docker-entrypoint-initdb.d" `
  mariadb:10.4