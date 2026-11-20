// Screen gallery: swaps the phone screenshot and its caption.
const shots = {
  'home-online': ['Статус видно сразу', 'Крупная надпись говорит, что происходит: «Вы дома», «Подключаемся», «На связи». Индикатор двигается, пока VPN подключается и работает.', 'Главный экран AutoTurn 2.0: VPN на связи'],
  'profiles': ['Основной и запасные', 'Один основной профиль и очередь резервов. Отключённые профили пропускаются, порядок меняется перетаскиванием.', 'Профили AutoTurn 2.0: основной, обычный и последний резервы'],
  'app-routing': ['Каждому приложению свой путь', 'Через VPN все приложения, только выбранные или все, кроме выбранных. Есть поиск и системные приложения.', 'Выбор приложений для VPN в AutoTurn 2.0'],
  'settings': ['Настроили один раз', 'Тема, домашние Wi-Fi сети, приложения, защищённый режим и возврат в Авто собраны на одном экране.', 'Настройки AutoTurn 2.0'],
  'home-light': ['И в светлой теме', 'Тёмная, светлая или как в системе: выбирается в настройках.', 'Главный экран AutoTurn 2.0 в светлой теме']
};
const buttons = document.querySelectorAll('[data-shot]');
buttons.forEach(button => button.addEventListener('click', () => {
  const key = button.dataset.shot;
  const [title, copy, alt] = shots[key];
  buttons.forEach(item => {
    item.classList.toggle('selected', item === button);
    item.setAttribute('aria-pressed', String(item === button));
  });
  const img = document.getElementById('shot');
  img.src = `images/${key}.png`;
  img.alt = alt;
  document.getElementById('shot-title').textContent = title;
  document.getElementById('shot-copy').textContent = copy;
}));
