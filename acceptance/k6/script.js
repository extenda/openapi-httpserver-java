import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '30s', target: 10 },
    { duration: '30s', target: 100 },
    { duration: '1m', target: 100 },
    { duration: '10s', target: 0 },
  ],
};

const body = JSON.stringify({
  id: 'some-id',
  age: 42,
  random: 'd5af5004-8b5a-4db6-838e-38be773eac34'
});

export default function () {
  const url = 'http://localhost:8080/api/v1/data';

  const res = http.post(url, body, {
    headers: {
      'Content-Type':'application/json',
    }
  });

  check(res, {
    'is status 200': (r) => r.status === 200,
    'is response in JSON format': (r) => r.headers['Content-Type'] === 'application/json',
    'id exists in response': (r) => JSON.parse(r.body).hasOwnProperty('id'),
  });
}
