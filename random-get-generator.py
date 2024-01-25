import string
import random


def generate_string(length=20):
    letters = string.ascii_letters + string.digits
    return ''.join(random.choice(letters) for _ in range(length))


if __name__ == "__main__":
    for i in range(1000):
        random_string = generate_string()
        command = f'curl -X GET "http://dh2020pc20.utm.utoronto.ca:8082/{random_string}" > /dev/null 2>&1 &'
        with open('crul-cmds-get.txt', 'a') as file:
            file.write(command + '\n')
