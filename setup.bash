# Passwordless ssh
ssh-keygen -t rsa
cp ~/.ssh/id_rsa.pub ~/.ssh/authorized_keys

touch ~/.bashrc
JAVA_HOME_LINE='export JAVA_HOME="/opt/jdk-20.0.1"'
PATH_LINE='export PATH="/opt/jdk-20.0.1/bin:$PATH"'
echo "$JAVA_HOME_LINE" >> ~/.bashrc
echo "$PATH_LINE" >> ~/.bashrc

 python3 ./monitor.py