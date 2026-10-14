/**
 * Отказ действия — field-backlog, пункт 73: одним видом на всех экранах.
 *
 * Отказ сервера видно ТАМ, ГДЕ НАЖАЛИ, — место у каждого экрана своё и остаётся своим, — но
 * выглядит он одинаково: знак ⚠, цвет тревоги и роль `alert`, чтобы читалка назвала его сразу.
 * Прежде у каждого экрана была своя строка красным своего кегля, и отказ на одном экране не
 * узнавался отказом на другом. Кегль и место задаёт экран (`className`), вид — этот компонент.
 */
export function ActionError({ message, className = '', as = 'p' }: {
  message: string;
  className?: string;
  as?: 'p' | 'span';
}) {
  const Tag = as;
  return (
    <Tag role="alert" className={(className ? className + ' ' : '') + 'text-danger'}>
      <span aria-hidden className="mr-1">
        ⚠
      </span>
      {message}
    </Tag>
  );
}
